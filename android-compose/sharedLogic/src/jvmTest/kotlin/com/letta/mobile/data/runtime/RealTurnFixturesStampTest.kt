package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * letta-mobile-jdcoj: replays the REAL turns captured for letta-mobile-bglj6.1.15 (4 models x 2
 * turns, each with a tool call and a two-paragraph streamed reply) through [TurnStreamIdentity].
 * The captured viewer wire is the host's already-cumulative output, so it is replayed as
 * [StreamTextFrameSource.CumulativeSnapshot].
 */
class RealTurnFixturesStampTest {

    @Test
    fun everyRealAssistantMessageGetsOneLogicalIdAndGrowingTextSeq() {
        forEachTurn { _, wire, stored ->
            var minted = 0
            val identity = TurnStreamIdentity("turn-x") { "lm-${++minted}" }
            val stamped = wire.mapNotNull { identity.stamp(it.toString(), StreamTextFrameSource.CumulativeSnapshot) }.map(::deltaOfBody)
            val assistants = stamped.filter { it.str("message_type") == "assistant_message" }
            assertEquals(assistants.map { it.str("id") }.toSet().size, assistants.map { it.str("logical_message_id") }.toSet().size)
            assistants.groupBy { it.str("logical_message_id") }.values.forEach { frames ->
                assertEquals((1..frames.size).toList(), frames.map { it["text_seq"]?.jsonPrimitive?.intOrNull })
                val id = frames.first().str("id")
                assertEquals(stored.getValue(id), frames.last().str("content"))
            }
        }
    }

    @Test
    fun realToolCallAndBothReturnFramesShareTheCallId() {
        forEachTurn { _, wire, _ ->
            var minted = 0
            val identity = TurnStreamIdentity("turn-x") { "lm-${++minted}" }
            val stamped = wire.mapNotNull { identity.stamp(it.toString(), StreamTextFrameSource.CumulativeSnapshot) }.map(::deltaOfBody)
            val call = stamped.single { it.str("message_type") == "tool_call_message" }
            val callId = call["tool_call"]!!.jsonObject.str("tool_call_id")
            assertEquals("tc-$callId", call.str("logical_message_id"))
            val returns = stamped.filter { it.str("message_type") == "tool_return_message" }
            assertEquals(2, returns.size)
            assertEquals(setOf("tr-$callId"), returns.map { it.str("logical_message_id") }.toSet())
        }
    }

    @Test
    fun twoRepliesInOneConversationKeepDistinctLogicalIds() {
        MODELS.forEach { model ->
            var minted = 0
            val identity = TurnStreamIdentity("turn-x") { "lm-${++minted}" }
            val replies = listOf(1, 2).map { turn ->
                wireFrames(model, turn).mapNotNull { identity.stamp(it.toString(), StreamTextFrameSource.CumulativeSnapshot) }
                    .map(::deltaOfBody).filter { it.str("message_type") == "assistant_message" }
                    .map { it.str("logical_message_id") }.toSet()
            }
            assertEquals(listOf(1, 1), replies.map { it.size }, model)
            assertNotEquals(replies[0], replies[1], model)
        }
    }

    private fun forEachTurn(block: (String, List<JsonObject>, Map<String, String>) -> Unit) {
        MODELS.forEach { model ->
            listOf(1, 2).forEach { turn ->
                block(model, wireFrames(model, turn), storedAssistantText(model, turn))
            }
        }
    }

    private fun wireFrames(model: String, turn: Int): List<JsonObject> =
        resource("$model/turn$turn.wire-viewer.jsonl").lines().filter { it.isNotBlank() }
            .map { AppServerProtocol.json.parseToJsonElement(it).jsonObject.getValue("wire").jsonObject }

    private fun storedAssistantText(model: String, turn: Int): Map<String, String> =
        AppServerProtocol.json.parseToJsonElement(resource("$model/turn$turn.message-list.json")).jsonArray
            .map { it.jsonObject }.filter { it.str("message_type") == "assistant_message" }
            .associate { it.str("id") to textOf(it["content"] as? JsonArray) }

    private fun textOf(parts: JsonArray?): String =
        parts.orEmpty().joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }

    private fun deltaOfBody(body: String): JsonObject =
        AppServerProtocol.json.parseToJsonElement(body).jsonObject.getValue("delta").jsonObject

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun resource(path: String): String {
        val stream = checkNotNull(javaClass.getResourceAsStream("/timeline/identity/real-turns/$path")) { "missing $path" }
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList() ?: emptyList()

    private companion object {
        val MODELS = listOf("minimax-m3", "qwen3.8-max", "claude-sonnet-5-5", "kat-coder-pro-v2.5")
    }
}
