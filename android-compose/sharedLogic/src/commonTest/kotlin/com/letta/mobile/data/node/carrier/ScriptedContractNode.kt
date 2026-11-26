package com.letta.mobile.data.node.carrier

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** The node-carrier contract fixture, `contracts/node-carrier/v1.json`, read once. */
object NodeCarrierFixture {
    private val root: JsonObject by lazy { Json.parseToJsonElement(nodeCarrierContractText).jsonObject }

    /** The scenarios in fixture order. */
    val scenarios: List<JsonObject> get() = root.getValue("scenarios").jsonArray.map { it.jsonObject }

    /** The scenario named [name]. */
    fun scenario(name: String): JsonObject = scenarios.single { it.string("name") == name }
}

/** The closure the scripted node uses when a frame does not match the script. */
val CONTRACT_MISMATCH: NodeLinkClosure = NodeLinkClosure(4900L, "contract_mismatch")

/**
 * A node that serves one fixture scenario from the node's end of any carrier. The n-th link it accepts plays the
 * n-th session the scenario opens: each control frame must equal the next control frame the script expects for that
 * session (its auth, then its requests), and is answered with the script's replies on their lanes; each call lane's
 * request must equal one the script lists for the session, and is answered on that lane. A mismatch is recorded and
 * closes the link with [CONTRACT_MISMATCH].
 */
class ScriptedContractNode(scenario: JsonObject) : NodePeerAcceptor {
    private val scripts: List<SessionScript> = sessionScripts(scenario)
    private val lock = Mutex()
    private var accepted = 0
    private val recorded = MutableStateFlow<List<String>>(emptyList())
    private val answered = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** Every mismatch so far, as readable text. */
    val failures: List<String> get() = recorded.value

    /** Completes with the closure the node observed on [session]'s link. */
    fun closureOf(session: String): CompletableDeferred<NodeLinkClosure> = script(session).closure

    /** Script entries no frame consumed. */
    fun unconsumed(): List<String> = scripts.flatMap { script ->
        script.control.map { "${script.label}: control ${it.expect}" } +
            script.calls.values.map { "${script.label}: call ${it.expect}" }
    }

    override suspend fun serve(link: NodePeerLink) {
        val index = lock.withLock { accepted++ }
        val script = scripts.getOrNull(index)
        if (script == null) {
            fail("link #$index has no session in the script")
            link.close(CONTRACT_MISMATCH)
            return
        }
        try {
            coroutineScope {
                launch { link.calls.collect { lane -> launch { answerCall(link, script, lane) } } }
                link.control.collect { frame -> answerControl(link, script, frame) }
            }
        } finally {
            script.closure.complete(link.closure.value ?: NodeLinkClosure.LOST)
        }
    }

    private suspend fun answerControl(link: NodePeerLink, script: SessionScript, frame: NodeFrame) {
        val expected = script.control.removeFirstOrNull()
        if (expected == null || expected.expect != frame.json) {
            mismatch(link, "${script.label}: control frame ${frame.json}, expected ${expected?.expect}")
            return
        }
        expected.replies.forEach { (lane, reply) ->
            if (lane == NodeLane.PUSH) link.push(NodeFrame(reply)) else link.sendControl(NodeFrame(reply))
        }
        expected.nodeClose?.let { link.close(it) }
    }

    private suspend fun answerCall(link: NodePeerLink, script: SessionScript, lane: NodeCallLane) {
        val request = lane.incoming.first()
        val expected = request.requestId?.let { id -> lock.withLock { script.calls.remove(id) } }
        if (expected == null || expected.expect != request.json) {
            mismatch(link, "${script.label}: call ${request.json}, expected ${expected?.expect}")
            return
        }
        expected.answerAfter.forEach { gate(it).await() }
        lane.send(NodeFrame(expected.response))
        lane.finishSending()
        gate(request.requestId.orEmpty()).complete(Unit)
    }

    private suspend fun gate(requestId: String): CompletableDeferred<Unit> =
        lock.withLock { answered.getOrPut(requestId) { CompletableDeferred() } }

    private suspend fun mismatch(link: NodePeerLink, text: String) {
        fail(text)
        link.close(CONTRACT_MISMATCH)
    }

    private fun fail(text: String) = recorded.update { it + text }

    private fun script(label: String): SessionScript = scripts.single { it.label == label }
}

/** What one session's link must carry, in order, from the node's point of view. */
private class SessionScript(val label: String) {
    val control = ArrayDeque<ControlExpectation>()
    val calls = mutableMapOf<String, CallExpectation>()
    val closure = CompletableDeferred<NodeLinkClosure>()
}

private class ControlExpectation(
    val expect: JsonObject,
    val replies: List<Pair<NodeLane, JsonObject>>,
    val nodeClose: NodeLinkClosure?,
)

private class CallExpectation(val expect: JsonObject, val response: JsonObject, val answerAfter: List<String>)

private fun sessionScripts(scenario: JsonObject): List<SessionScript> {
    val byLabel = linkedMapOf<String, SessionScript>()
    for (step in scenario.getValue("steps").jsonArray.map { it.jsonObject }) {
        val label = step.string("session")
        when (step.string("op")) {
            "open" -> byLabel.getOrPut(label) { SessionScript(label) }.control +=
                ControlExpectation(step.obj("expect_frame"), replies(step), step["node_close"]?.let(::closureOf))
            "request" -> byLabel.getValue(label).control += ControlExpectation(step.obj("frame"), replies(step), null)
            "call" -> byLabel.getValue(label).addCall(step)
            "calls", "resume" -> step.getValue("calls").jsonArray.forEach { byLabel.getValue(label).addCall(it.jsonObject) }
        }
    }
    return byLabel.values.toList()
}

private fun SessionScript.addCall(call: JsonObject) {
    val expect = call.obj("expect_frame")
    calls[expect.string("request_id")] = CallExpectation(
        expect = expect,
        response = call.obj("response"),
        answerAfter = (call["answer_after"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
    )
}

private fun replies(step: JsonObject): List<Pair<NodeLane, JsonObject>> = step.getValue("replies").jsonArray.map {
    val reply = it.jsonObject
    val lane = if (reply.string("lane") == "push") NodeLane.PUSH else NodeLane.CONTROL
    lane to reply.obj("frame")
}

/** A fixture closure, `{"code": n, "reason": "..."}`. */
fun closureOf(element: JsonElement): NodeLinkClosure {
    val closure = element.jsonObject
    return NodeLinkClosure(closure.getValue("code").jsonPrimitive.long, closure.string("reason"))
}

/** The string at [key]. */
fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

/** The object at [key]. */
fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

/** The object at [key], or null when it is absent or JSON null. */
fun JsonObject.objOrNull(key: String): JsonObject? = get(key)?.takeIf { it != JsonNull }?.jsonObject
