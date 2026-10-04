package com.letta.mobile.data.timeline

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One `stream_delta` wire line, the way the App Server sends it to a viewer. */
internal data class RawDelta(
    val eventSeq: Int,
    val messageType: String,
    val content: String? = null,
    val id: String? = null,
    val otid: String? = null,
    val runId: String? = null,
    /** The identity stamp (letta-mobile-jdcoj) a stamped host adds: logical_message_id and text_seq. */
    val stamp: Pair<String, Int>? = null,
)

internal object RawWireFrames {
    const val AGENT = "agent-raw"
    const val CONVERSATION = "conv-raw"

    fun line(delta: RawDelta): String = buildJsonObject {
        put("type", "stream_delta")
        put("runtime", buildJsonObject { put("agent_id", AGENT); put("conversation_id", CONVERSATION) })
        put("event_seq", delta.eventSeq)
        put("emitted_at", "2026-10-03T12:00:00.000Z")
        put("idempotency_key", "key-${delta.eventSeq}-${delta.messageType}-${delta.content?.length}")
        put("delta", body(delta))
    }.toString()

    private fun body(delta: RawDelta): JsonObject = buildJsonObject {
        put("message_type", delta.messageType)
        delta.id?.let { put("id", it) }
        delta.otid?.let { put("otid", it) }
        delta.runId?.let { put("run_id", it) }
        delta.stamp?.let { (logicalId, seq) -> put("logical_message_id", logicalId); put("text_seq", seq) }
        delta.content?.let { put("content", JsonPrimitive(it)) }
    }

    /** [texts] as cumulative assistant snapshots of ONE message, with no id and no otid. */
    fun anonymousReply(firstSeq: Int, texts: List<String>): List<String> =
        texts.mapIndexed { index, text -> line(RawDelta(firstSeq + index, "assistant_message", text)) }

    /** [texts] as cumulative assistant snapshots of one reply, each under a fresh id of [run]. */
    fun rotatingReply(firstSeq: Int, run: String, label: String, texts: List<String>): List<String> =
        texts.mapIndexed { index, text ->
            line(RawDelta(firstSeq + index, "assistant_message", text, id = "letta-msg-$label-$index", runId = run))
        }

    fun resource(path: String): List<String> =
        (RawWireFrames::class.java.getResourceAsStream(path) ?: error("missing test resource $path"))
            .bufferedReader().use { it.readLines() }.filter(String::isNotBlank)
}
