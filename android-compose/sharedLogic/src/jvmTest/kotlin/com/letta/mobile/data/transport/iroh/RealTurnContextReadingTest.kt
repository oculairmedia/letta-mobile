package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-r2zo8: a real captured turn (claude-sonnet-5-5, two model calls) replayed through
 * the Iroh mapper into the context readings. The wire's `context_tokens` must survive the mapper
 * and the last call's count must be the conversation's reading.
 */
class RealTurnContextReadingTest {

    @Test
    fun theLastModelCallsContextTokensIsTheConversationsReading() = runTest {
        val frames = wire("claude-sonnet-5-5", 1).flatMap { envelope ->
            IrohStreamDeltaServerFrameMapper.map(
                payload = RuntimeEventPayload.RemoteStreamFrame(
                    frameId = "f",
                    messageId = null,
                    messageType = null,
                    body = envelope.toString(),
                ),
                context = CONTEXT,
            )
        }
        val usage = frames.filterIsInstance<ServerFrame.UsageStatistics>()
        val readings = ContextTokenReadings()

        frames.forEach { readings.record(it) }

        assertEquals(listOf(45_117L, 45_515L), usage.map { it.contextTokens })
        // The second call's prompt_tokens is only the uncached tail; the total is context_tokens.
        assertEquals(106L, usage.last().promptTokens)
        assertEquals(45_515, readings.latest("probe", "local-conv-575"))
    }

    private fun wire(model: String, turn: Int): List<JsonObject> {
        val path = "/timeline/identity/real-turns/$model/turn$turn.wire-viewer.jsonl"
        val text = checkNotNull(javaClass.getResourceAsStream(path)).bufferedReader().readText()
        return text.lines().filter { it.isNotBlank() }
            .map { AppServerProtocol.json.parseToJsonElement(it).jsonObject.getValue("wire").jsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "stream_delta" }
    }

    private companion object {
        val CONTEXT = IrohStreamDeltaServerFrameMapper.Context(
            agentId = "fallback-agent",
            conversationId = "fallback-conv",
            turnId = null,
            runId = null,
            timestamp = "2026-10-03T00:00:00Z",
        )
    }
}
