package com.letta.mobile.data.node.carrier

import com.letta.mobile.data.feed.wire.FeedCursor
import com.letta.mobile.data.feed.wire.FeedPage
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.serialization.LettaJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * A running node end for the suite: a carrier that dials it at [address], an address nothing answers, and how to
 * stop it.
 */
class NodeCarrierEndpoint(
    /** The carrier under test, dialling the node end. */
    val carrier: NodeCarrier,
    /** The address the node end answers. */
    val address: NodeAddress,
    /** An address this carrier accepts the form of, but where no node answers. */
    val unreachable: NodeAddress,
    /** Stops the node end and releases what [NodeCarrierHarness.start] opened. */
    val stop: suspend () -> Unit,
)

/** How the suite gets a carrier under test. Each carrier's test supplies one. */
fun interface NodeCarrierHarness {
    /**
     * Starts a node end that hands every link a peer opens to [acceptor] (in [scope]), and returns the carrier that
     * dials it. The in-process harness wires its carrier straight to [acceptor]; the Iroh harness binds a loopback
     * endpoint whose accepted connections are wrapped as [NodePeerLink]s and handed to [acceptor].
     */
    suspend fun start(acceptor: NodePeerAcceptor, scope: CoroutineScope): NodeCarrierEndpoint
}

/**
 * The node-carrier contract, one test per scenario of `contracts/node-carrier/v1.json`: auth, capability gate,
 * admin_rpc, turn, detach and feed resume. A carrier is conformant when a subclass that supplies its [harness] passes.
 * Each scenario runs [NodeSession] over the carrier against [ScriptedContractNode] serving the same scenario, so a
 * frame lost, reordered, altered or delivered on the wrong lane fails it, as does a closure not delivered unchanged.
 */
abstract class NodeCarrierContractSuite {
    /** The carrier under test. */
    protected abstract fun harness(): NodeCarrierHarness

    @Test
    fun auth() = runScenario("auth")

    @Test
    fun capabilityGate() = runScenario("capability_gate")

    @Test
    fun adminRpc() = runScenario("admin_rpc")

    @Test
    fun turn() = runScenario("turn")

    @Test
    fun detach() = runScenario("detach")

    @Test
    fun feedResume() = runScenario("feed_resume")

    @Test
    fun dialingAnAddressWhereNoNodeAnswersIsUnreachable() = onCarrier(ScriptedContractNode(emptyScenario())) { endpoint, _ ->
        val failure = runCatching { endpoint.carrier.dial(endpoint.unreachable) }.exceptionOrNull()
        assertTrue((failure as? NodeCarrierException)?.failure is NodeCarrierFailure.Unreachable, "dial failed with $failure")
    }

    private fun runScenario(name: String) {
        val node = ScriptedContractNode(NodeCarrierFixture.scenario(name))
        onCarrier(node) { endpoint, scope ->
            val outcome = runCatching { ScenarioRun(endpoint, node, scope).run(NodeCarrierFixture.scenario(name)) }
            outcome.exceptionOrNull()?.let { fail("scenario $name failed: $it; node saw: ${node.failures}", it) }
            assertEquals(emptyList(), node.failures, "scenario $name: node-side mismatches")
            assertEquals(emptyList(), node.unconsumed(), "scenario $name: frames the script expected but never received")
        }
    }

    private fun onCarrier(
        node: ScriptedContractNode,
        body: suspend (NodeCarrierEndpoint, CoroutineScope) -> Unit,
    ) = runTest(timeout = SCENARIO_TIMEOUT) {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val endpoint = harness().start(node, scope)
            try {
                withTimeout(SCENARIO_TIMEOUT) { body(endpoint, scope) }
            } finally {
                endpoint.stop()
                scope.cancel()
            }
        }
    }

    private companion object {
        val SCENARIO_TIMEOUT = 20.seconds

        fun emptyScenario(): JsonObject = buildJsonObject { put("steps", JsonArray(emptyList())) }
    }
}

/** Runs one scenario's steps from the shell's end. */
private class ScenarioRun(
    private val endpoint: NodeCarrierEndpoint,
    private val node: ScriptedContractNode,
    private val scope: CoroutineScope,
) {
    private val sessions = mutableMapOf<String, NodeSession>()

    suspend fun run(scenario: JsonObject) {
        for (step in scenario.getValue("steps").jsonArray.map { it.jsonObject }) {
            val label = step.string("session")
            when (val op = step.string("op")) {
                "open" -> open(label, step)
                "request" -> request(sessions.getValue(label), step)
                "call" -> call(sessions.getValue(label), step)
                "calls" -> calls(sessions.getValue(label), step)
                "resume" -> resume(sessions.getValue(label), step)
                "close" -> close(label)
                else -> fail("unknown step op $op")
            }
        }
    }

    private suspend fun open(label: String, step: JsonObject) {
        var minted = 0
        val credential = step.obj("credential")
        val request = NodeOpenRequest(
            address = endpoint.address,
            credential = if (credential.string("kind") == "token") {
                NodeCredential.Token(credential.string("token"))
            } else {
                NodeCredential.CarrierIdentity
            },
            peerName = step["peer_name"]?.jsonPrimitive?.content,
        )
        val outcome = runCatching { NodeSession.open(endpoint.carrier, request, scope) { "$label-${++minted}" } }
        val expect = step.obj("expect")
        if ("auth_rejected" in expect) {
            val failure = (outcome.exceptionOrNull() as? NodeCarrierException)?.failure
            val rejected = NodeCarrierFailure.AuthRejected(expect.string("auth_rejected"))
            val closed = expect.objOrNull("or_closed")?.let { NodeCarrierFailure.Closed(closureOf(it)) }
            assertTrue(failure == rejected || (closed != null && failure == closed), "$label: open failed with $failure, expected $rejected or $closed")
            return
        }
        val session = outcome.getOrThrow()
        assertEquals(expect.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }.toSet(), session.capabilities.host)
        assertEquals(expect.getValue("paired").jsonPrimitive.boolean, session.capabilities.paired)
        expect.objOrNull("supports")?.forEach { (capability, supported) ->
            assertEquals(supported.jsonPrimitive.boolean, session.capabilities.supports(capability), "$label supports $capability")
        }
        sessions[label] = session
    }

    private suspend fun request(session: NodeSession, step: JsonObject) {
        val frame = NodeFrame(step.obj("frame"))
        assertEquals(frame.requestId, session.nextRequestId(), "the fixture's request id is the session's next one")
        val response = session.request(frame)
        val replies = step.getValue("replies").jsonArray.map { it.jsonObject }
        val answer = replies.first { it.string("lane") == "control" && it.obj("frame").string("request_id") == frame.requestId }
        assertEquals(answer.obj("frame"), response.json)
        val pushes = replies.filter { it.string("lane") == "push" }.map { it.obj("frame") }
        if (pushes.isEmpty()) return
        val received = session.inbound.filter { it.lane == NodeLane.PUSH }.take(pushes.size).toList().map { it.frame.json }
        assertEquals(pushes, received, "pushes arrive on the push lane, in order")
    }

    private suspend fun call(session: NodeSession, step: JsonObject) {
        assertEquals(step.obj("response"), session.adminRpc(step.string("method"), step.objOrNull("params")).json)
    }

    private suspend fun calls(session: NodeSession, step: JsonObject) = coroutineScope {
        val calls = step.getValue("calls").jsonArray.map { it.jsonObject }
        val answers = calls.map { call ->
            async(start = CoroutineStart.UNDISPATCHED) { session.adminRpc(call.string("method"), call.objOrNull("params")) }
        }.awaitAll()
        calls.zip(answers).forEach { (call, answer) -> assertEquals(call.obj("response"), answer.json) }
    }

    private suspend fun resume(session: NodeSession, step: JsonObject) {
        val from = step.objOrNull("from")?.let { FeedCursor(it.getValue("epoch").jsonPrimitive.long, it.getValue("seq").jsonPrimitive.long) }
        val outcome = runCatching {
            session.resumeFeed(ConversationId(step.string("conversation_id")), AgentId(step.string("agent_id")), from)
        }
        val refused = step.objOrNull("expect")?.get("refused")?.jsonPrimitive?.content
        if (refused != null) {
            val failure = (outcome.exceptionOrNull() as? NodeCarrierException)?.failure
            assertEquals(NodeCarrierFailure.Refused("feed.subscribe", refused), failure)
            return
        }
        val expected = step.getValue("calls").jsonArray.map {
            LettaJson.wire.decodeFromJsonElement(FeedPage.serializer(), it.jsonObject.obj("response").obj("result"))
        }
        assertEquals(expected, outcome.getOrThrow())
    }

    private suspend fun close(label: String) {
        val session = sessions.remove(label) ?: fail("$label is not open")
        session.close()
        assertEquals(NodeLinkClosure.NORMAL, session.closure.value, "$label: closure is set when close returns")
        assertEquals(NodeLinkClosure.NORMAL, node.closureOf(label).await(), "$label: the node sees an orderly close")
        val late = runCatching { session.send(NodeFrame(buildJsonObject { put("type", "sync") })) }.exceptionOrNull()
        assertTrue((late as? NodeCarrierException)?.failure is NodeCarrierFailure.Closed, "$label: a send after close failed with $late")
    }
}
