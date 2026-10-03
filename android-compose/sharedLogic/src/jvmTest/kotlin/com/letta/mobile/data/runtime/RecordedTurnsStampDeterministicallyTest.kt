package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.node.iroh.CumulativeStreamText
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-jdcoj: pins the "append-always" assumption against recorded REAL App Server turns.
 * For every stream_delta text frame in the fixtures, [TurnStreamIdentity] (append, no `startsWith`
 * sniffing) must emit exactly the text the fanout's former [CumulativeStreamText] emitted, so
 * removing the sniff changes nothing on real data. A disagreement here is the plan's section 3
 * risk: stop and decide with the owner, do not add a sniff back.
 */
class RecordedTurnsStampDeterministicallyTest {

    @Test
    fun irohCapturedAssistantTurnStampsToTheTextTheAccumulatorProduced() {
        val frames = fixtureLines("/iroh_real_frames_h30cy.jsonl").mapIndexed { index, line ->
            envelope(AppServerProtocol.json.parseToJsonElement(line).jsonObject, "iroh-$index")
        }
        assertTrue(frames.size > 100, "expected the recorded turn")
        assertStampedTextMatchesAccumulator(frames)
    }

    @Test
    fun appServerProtocolFixtureStampsToTheTextTheAccumulatorProduced() {
        val frames = fixtureLines("/appserver/protocol-frames.jsonl").filter { it.contains("\"stream_delta\"") }
        assertTrue(frames.isNotEmpty(), "expected stream_delta frames in the protocol fixture")
        assertStampedTextMatchesAccumulator(frames)
    }

    private fun assertStampedTextMatchesAccumulator(frames: List<String>) {
        val identity = TurnStreamIdentity("turn-fixture") { "lm-fixture" }
        val accumulator = CumulativeStreamText()
        val stamped = frames.mapNotNull { identity.stamp(it, StreamTextFrameSource.AppServerDelta) }
        val expected = frames.map { accumulator.applyToRawFrame(it, StreamTextFrameSource.AppServerDelta) }
        assertEquals(expected.map(::textOf), stamped.map(::textOf))
        assertEquals(expected.last().let(::textOf), finalTextPerLogicalId(stamped).values.single())
    }

    private fun finalTextPerLogicalId(stamped: List<String>): Map<String, String?> =
        stamped.associate { body ->
            val delta = body.delta()
            delta.getValue("logical_message_id").jsonPrimitive.content to textOf(body)
        }

    private fun textOf(body: String): String? {
        val delta = body.delta()
        return (delta["content"] ?: delta["reasoning"]).let { it?.jsonPrimitive?.contentOrNull }
    }

    private fun String.delta(): JsonObject =
        AppServerProtocol.json.parseToJsonElement(this).jsonObject.getValue("delta").jsonObject

    private fun envelope(delta: JsonObject, key: String): String = buildJsonObject {
        put("type", "stream_delta")
        put("idempotency_key", key)
        put("delta", delta)
    }.toString()

    private fun fixtureLines(resource: String): List<String> {
        val stream = checkNotNull(javaClass.getResourceAsStream(resource)) { "missing fixture $resource" }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readLines() }.filter { it.isNotBlank() }
    }
}
