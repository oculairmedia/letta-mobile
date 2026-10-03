package com.letta.mobile.data.plugin.wire

import com.letta.mobile.plugin.api.LcpDirection
import com.letta.mobile.plugin.api.LcpMethod
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Plays the plugin's side of a transcript: sends its lines and holds what the host sends to the transcript's. */
internal class TranscriptPlayer(private val transport: LcpTransport) {
    suspend fun play(lines: List<WireLine>) = lines.forEach { line ->
        if (line.dir == LcpDirection.PLUGIN_TO_HOST) transport.send(JsonRpcCodec.encode(line.message)) else expect(line.message)
    }

    private suspend fun expect(expected: JsonRpcMessage) {
        val frame = assertIs<LcpInboundFrame.Text>(transport.receive(), "the host stopped before $expected")
        val actual = assertIs<JsonRpcDecoding.Decoded>(JsonRpcCodec.decode(frame.text)).message
        assertEquals(normalized(JsonRpcCodec.toJson(expected)), normalized(JsonRpcCodec.toJson(actual)))
    }
}

/** Drives the host's side of a transcript: makes each call the host makes there and holds the answer to the recorded one. */
internal class HostDriver(private val peer: LcpPeer, private val lines: List<WireLine>) {
    suspend fun drive() = lines.filter { it.dir == LcpDirection.HOST_TO_PLUGIN }.forEach { line ->
        when (val message = line.message) {
            is JsonRpcMessage.Request -> request(message)
            is JsonRpcMessage.Notification -> peer.notify(checkNotNull(LcpMethod.byWire(message.method)), message.params)
            else -> Unit
        }
    }

    private suspend fun request(request: JsonRpcMessage.Request) {
        val method = checkNotNull(LcpMethod.byWire(request.method))
        when (val answer = answerTo(request)) {
            is JsonRpcMessage.Success -> assertEquals(normalized(answer.result), normalized(peer.request(method, request.params)), request.method)
            is JsonRpcMessage.Failure -> assertEquals(answer.error.code, assertFailsWith<LcpCallException> { peer.request(method, request.params) }.code)
            else -> error("no answer to ${request.id}")
        }
    }

    private fun answerTo(request: JsonRpcMessage.Request): JsonRpcMessage? = lines
        .filter { it.dir == LcpDirection.PLUGIN_TO_HOST }
        .map { it.message }
        .firstOrNull { (it as? JsonRpcMessage.Success)?.id == request.id || (it as? JsonRpcMessage.Failure)?.id == request.id }
}
