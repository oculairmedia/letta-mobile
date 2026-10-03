package com.letta.mobile.data.runtime

import kotlinx.serialization.json.JsonObject

/** One stored App Server row, as the host's `message.list` serves it: its durable id and `message_type`. */
data class StoredRowRef(val durableId: String, val messageType: String)

/** The identity a stored row carries on the wire: `logical_message_id` and `turn_id`. */
data class RowIdentity(val logicalMessageId: String, val turnId: String)

/** Per-conversation persistence of the stored-row to identity map. */
interface TurnIdentityStore {
    suspend fun load(conversationId: String): Map<StoredRowRef, RowIdentity>

    suspend fun append(conversationId: String, entries: Map<StoredRowRef, RowIdentity>)
}

/** The conversation's stored rows, newest first, as the App Server lists them. */
fun interface ConversationRowsSource {
    suspend fun newestRows(conversationId: String, limit: Int): List<JsonObject>
}

/**
 * One logical message a turn streamed. [finalText] is the last cumulative text of an assistant /
 * reasoning message, [toolCallId] ties tool call and return rows, [otid] ties the user row, and
 * [upstreamId] is the App Server's own stable message id from the stream (`delta.id`), which the
 * captured real turns prove equals the stored assistant / reasoning row id.
 */
data class SettledMessage(
    val logicalId: String,
    val messageType: String,
    val finalText: String? = null,
    val toolCallId: String? = null,
    val otid: String? = null,
    val upstreamId: String? = null,
)

/** A finished turn: its id and its logical messages in stream order. */
data class SettledTurn(val turnId: String, val messages: List<SettledMessage>)

/** Result of joining a turn to stored rows: how many matched and the logical ids that did not. */
data class TurnMatchReport(val matched: Int, val unmatched: List<String>)
