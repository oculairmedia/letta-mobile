package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-jdcoj: the raw, pre-fanout App Server deltas captured for letta-mobile-bglj6.1.15
 * (`raw-upstream/`, pure increments) and the viewer-wire turns, replayed through
 * [TurnStreamIdentity]. The stored `message.list` rows are the oracle.
 */
class RawUpstreamFixturesStampTest {

    @Test
    fun rawUpstreamIncrementsStampToCumulativeTextEqualToTheStoredRows() {
        RAW.forEach { name ->
            val stamped = stampedRaw(name)
            val stored = storedText(resource("raw-upstream/$name.message-list.json"))
            val finals = stamped.groupBy { it.str("logical_message_id") }
            assertEquals(stored.keys, finals.keys, name)
            finals.forEach { (id, frames) ->
                assertEquals((1..frames.size).toList(), frames.map { it["text_seq"]?.jsonPrimitive?.intOrNull }, "$name $id")
                assertEquals(stored.getValue(id), frames.last().let { it.str("content").ifEmpty { it.str("reasoning") } }, "$name $id")
            }
        }
    }

    @Test
    fun reasoningUsesTheWirePartIdAndTwoPartsInOneRunStayDistinct() {
        val reasoning = stampedRaw("or-glm-5.3-flash").filter { it.str("message_type") == "reasoning_message" }
        assertEquals(
            setOf("ui-msg-9184658:reasoning:0", "ui-msg-9184660:reasoning:0"),
            reasoning.map { it.str("logical_message_id") }.toSet(),
        )
        assertTrue(reasoning.all { it.str("logical_message_id") == it.str("id") }, "stable on every frame")
    }

    @Test
    fun grokWritesTwoAssistantMessagesInOneTurnWithDistinctLogicalIds() {
        val assistants = viewerTurn("openrouter-grok-4.7", 1).filter { it.str("message_type") == "assistant_message" }
        assertEquals(
            setOf("ui-msg-9184646", "ui-msg-9184648:assistant:1"),
            assistants.map { it.str("logical_message_id") }.toSet(),
        )
        val preamble = assistants.filter { it.str("logical_message_id") == "ui-msg-9184646" }
        assertEquals((1..preamble.size).toList(), preamble.map { it["text_seq"]?.jsonPrimitive?.intOrNull })
    }

    @Test
    fun geminiReasoningArrivesAsOneCompleteFrameWithTextSeqOne() {
        val reasoning = viewerTurn("openrouter-gemini-3.8-flash", 1).filter { it.str("message_type") == "reasoning_message" }
        assertEquals(listOf(1), reasoning.map { it["text_seq"]?.jsonPrimitive?.intOrNull })
        assertEquals("ui-msg-9184614:reasoning:0", reasoning.single().str("logical_message_id"))
    }

    private fun stampedRaw(name: String): List<JsonObject> {
        val identity = TurnStreamIdentity("turn-x") { "lm-minted" }
        return lines("raw-upstream/$name.wire.jsonl").map { it.getValue("wire").jsonObject }
            .filter { it.str("type") == "stream_delta" }
            .mapNotNull { identity.stamp(it.toString(), StreamTextFrameSource.AppServerDelta) }
            .map { deltaOf(it) }.filter { it.str("message_type") in TEXT_TYPES }
    }

    private fun viewerTurn(model: String, turn: Int): List<JsonObject> {
        val identity = TurnStreamIdentity("turn-x") { "lm-minted" }
        return lines("$model/turn$turn.wire-viewer.jsonl").map { it.getValue("wire").jsonObject }
            .mapNotNull { identity.stamp(it.toString(), StreamTextFrameSource.CumulativeSnapshot) }
            .map { deltaOf(it) }
    }

    private fun storedText(json: String): Map<String, String> =
        AppServerProtocol.json.parseToJsonElement(json).jsonArray.map { it.jsonObject }
            .filter { it.str("message_type") in TEXT_TYPES }
            .associate { row ->
                row.str("id") to (row["content"] as? JsonArray)?.joinToString("") { part ->
                    part.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }.orEmpty().ifEmpty { row.str("reasoning") }
            }

    private fun deltaOf(body: String): JsonObject =
        AppServerProtocol.json.parseToJsonElement(body).jsonObject.getValue("delta").jsonObject

    private fun lines(path: String): List<JsonObject> =
        resource(path).lines().filter { it.isNotBlank() }.map { AppServerProtocol.json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonElement)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun resource(path: String): String {
        val stream = checkNotNull(javaClass.getResourceAsStream("/timeline/identity/real-turns/$path")) { "missing $path" }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private companion object {
        val TEXT_TYPES = setOf("assistant_message", "reasoning_message")
        val RAW = listOf("MiniMax-M3", "claude-sonnet-5-5", "or-glm-5.3-flash", "or-gpt-6.1-sol", "or-grok-4.7", "or-qwen3.8-flash")
    }
}
