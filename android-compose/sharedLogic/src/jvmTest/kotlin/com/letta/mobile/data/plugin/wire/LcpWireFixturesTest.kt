package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.EmitReceipt
import com.letta.mobile.plugin.api.LcpDirection
import com.letta.mobile.plugin.api.LcpMethod
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginSecrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The golden transcripts of LCP wire v1 under `commonTest/resources/canvas/plugin/v1/wire/`
 * (letta-mobile-s416w.25): the spec a non-Kotlin plugin is written against. Every line decodes,
 * travels in its method's direction, holds to its method's typed shape exactly (re-encoding loses
 * nothing) and answers a request of the other side; every method has a transcript; the recorded
 * process session replays against the real host session byte for byte; the malformed lines are
 * refused with their codes.
 */
class LcpWireFixturesTest {
    private val json = Json

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("$DIR/$name")) { "missing fixture $name" }.readText()

    private fun transcriptNames(): List<String> =
        File(checkNotNull(javaClass.getResource(DIR)).toURI()).list().orEmpty().filter { it.endsWith(".jsonl") && it != MALFORMED }.sorted()

    private fun lines(name: String): List<WireLine> = resource(name).lines().filter(String::isNotBlank).map { raw ->
        val entry = json.parseToJsonElement(raw).jsonObject
        val dir = LcpDirection.entries.single { it.label == entry.getValue("dir").jsonPrimitive.content }
        WireLine(dir, assertIs<JsonRpcDecoding.Decoded>(JsonRpcCodec.decode(entry.getValue("message")), raw).message)
    }

    @Test
    fun everyTranscriptLineHoldsToItsMethodsShape() {
        transcriptNames().forEach { name -> TranscriptChecker(name).check(lines(name)) }
    }

    @Test
    fun everyMethodHasAGoldenTranscript() {
        val covered = transcriptNames().flatMap(::lines).mapNotNull { methodOf(it.message) }.toSet()
        assertEquals(LcpMethod.entries.toSet(), covered)
    }

    @Test
    fun theRecordedProcessSessionReplaysAgainstTheHost() = runTest {
        val transcript = lines("session-process.jsonl")
        val ends = LoopbackLcpTransport.pair()
        val binding = LcpHostBinding(PluginCapability.entries.toSet(), PluginSecrets().scrubber(), RecordingHost())
        val host = LcpHostSession(ends.first, binding, backgroundScope)
        val player = launch { TranscriptPlayer(ends.second).play(transcript) }
        HostDriver(host.peer, transcript).drive()
        player.join()
        assertEquals(LcpSessionState.CLOSED, host.state.value)
    }

    @Test
    fun malformedLinesAreRefusedWithTheirCodes() = runTest {
        val ends = LoopbackLcpTransport.pair()
        val peer = bareHost(ends.first, backgroundScope)
        resource(MALFORMED).lines().filter(String::isNotBlank).forEach { raw ->
            val case = json.parseToJsonElement(raw).jsonObject
            ends.second.send(case.getValue("raw").jsonPrimitive.content)
            if (case.getValue("answered").jsonPrimitive.boolean) {
                assertEquals(case.getValue("code").jsonPrimitive.int, nextError(ends.second).code, raw)
            } else {
                assertEquals(case.getValue("code").jsonPrimitive.int, (JsonRpcCodec.decode(case.getValue("raw").jsonPrimitive.content) as JsonRpcDecoding.Malformed).error.code, raw)
            }
        }
        ends.second.send("""{"jsonrpc":"2.0","id":"probe","method":"plugin.unknown"}""")
        assertEquals(LcpErrorCode.METHOD_NOT_FOUND, nextError(ends.second).code, "unanswered lines sent nothing back")
        peer.close()
    }

    private fun bareHost(transport: LcpTransport, scope: CoroutineScope): LcpPeer = LcpPeer(transport, LcpPeerConfig(LcpSide.HOST), scope).apply {
        serve(LcpCalls.EMIT) { EmitResult(EmitReceipt()) }
        serve(LcpCalls.LOG) {}
        start()
    }

    private suspend fun nextError(transport: LcpTransport): JsonRpcError {
        val text = assertIs<LcpInboundFrame.Text>(transport.receive()).text
        return assertIs<JsonRpcMessage.Failure>(assertIs<JsonRpcDecoding.Decoded>(JsonRpcCodec.decode(text)).message).error
    }

    private companion object {
        const val DIR = "/canvas/plugin/v1/wire"
        const val MALFORMED = "malformed.jsonl"
    }
}

/** One line of a transcript: who sent it, and what. */
internal data class WireLine(val dir: LcpDirection, val message: JsonRpcMessage)

/** The method a call names, or null for an answer. */
internal fun methodOf(message: JsonRpcMessage): LcpMethod? = when (message) {
    is JsonRpcMessage.Request -> LcpMethod.byWire(message.method)
    is JsonRpcMessage.Notification -> LcpMethod.byWire(message.method)
    else -> null
}

/** [element] with every number as a double, so `320` and `320.0` compare equal. */
internal fun normalized(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (_, value) -> normalized(value) })
    is JsonArray -> JsonArray(element.map(::normalized))
    is JsonPrimitive -> if (!element.isString && element.doubleOrNull != null) JsonPrimitive(element.double) else element
}

/** Holds one transcript to the typed registry: shapes, directions and id correlation. */
private class TranscriptChecker(private val name: String) {
    private val open = mutableMapOf<Pair<LcpDirection, JsonPrimitive>, LcpMethod>()

    fun check(lines: List<WireLine>) = lines.forEach { check(it.dir, it.message) }

    private fun check(dir: LcpDirection, message: JsonRpcMessage) {
        when (message) {
            is JsonRpcMessage.Request -> call(dir, message.method, message.params).also { open[dir to message.id] = it }
            is JsonRpcMessage.Notification -> call(dir, message.method, message.params)
            is JsonRpcMessage.Success -> success(answered(dir, message.id), message.result)
            is JsonRpcMessage.Failure -> failure(dir, message)
        }
    }

    private fun call(dir: LcpDirection, wire: String, params: JsonObject): LcpMethod {
        val method = checkNotNull(LcpMethod.byWire(wire)) { "$name: unknown method $wire" }
        assertTrue(method.direction == dir || method.direction == LcpDirection.EITHER, "$name: $wire sent $dir")
        assertRoundTrips(LcpCalls.of(method), params)
        return method
    }

    private fun answered(dir: LcpDirection, id: JsonPrimitive): LcpMethod {
        val asker = if (dir == LcpDirection.HOST_TO_PLUGIN) LcpDirection.PLUGIN_TO_HOST else LcpDirection.HOST_TO_PLUGIN
        return checkNotNull(open.remove(asker to id)) { "$name: answer $id to no open request" }
    }

    private fun success(method: LcpMethod, result: JsonElement) {
        val type = assertIs<LcpRequestType<*, *>>(LcpCalls.of(method))
        assertEquals(normalized(result), normalized(reencodeResult(type, result)), "$name: result of ${method.wire}")
    }

    private fun failure(dir: LcpDirection, message: JsonRpcMessage.Failure) {
        message.id?.let { answered(dir, it) }
        assertTrue(message.error.code in LcpErrorCode.meanings, "$name: unknown code ${message.error.code}")
    }

    private fun <P> assertRoundTrips(type: LcpCallType<P>, params: JsonObject) {
        assertEquals(normalized(params), normalized(type.encodeParams(type.decodeParams(params))), "$name: params of ${type.method.wire}")
    }

    private fun <P, R> reencodeResult(type: LcpRequestType<P, R>, result: JsonElement): JsonElement = type.encodeResult(type.decodeResult(result))
}
