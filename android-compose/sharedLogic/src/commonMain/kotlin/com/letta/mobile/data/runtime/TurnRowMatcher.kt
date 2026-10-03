package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.iroh.canonicalToolCalls
import com.letta.mobile.data.transport.iroh.canonicalToolReturn
import kotlinx.serialization.json.JsonObject

/** What one join attempt produced: the identities to persist and the messages no stored row matched. */
internal data class RowMatch(
    val entries: Map<StoredRowRef, RowIdentity>,
    val unmatched: List<SettledMessage>,
)

/**
 * The exact stream-to-stored join (plan 1.4). [rows] are the conversation's stored rows oldest
 * first; [claimed] are rows that already carry an identity and are never matched again.
 *
 * Keys, strongest first: user by `otid`; tool call / return by `tool_call_id`; assistant /
 * reasoning by the App Server's stable message id. Only a message that carries no such id falls
 * back to (message_type, exact final text), with
 * stored order breaking ties between identical texts. A message nothing matches is reported, never
 * guessed.
 */
internal class TurnRowMatcher(
    private val turnId: String,
    private val rows: List<JsonObject>,
    claimed: Set<StoredRowRef>,
) {
    private val taken = claimed.toMutableSet()
    private val entries = LinkedHashMap<StoredRowRef, RowIdentity>()

    fun match(messages: List<SettledMessage>): RowMatch {
        val afterKeys = messages.filterNot { matchKeyed(it) }
        val (textual, hopeless) = afterKeys.partition { it.messageType in TEXT_MESSAGE_TYPES && it.upstreamId == null }
        val unmatched = hopeless + matchByText(textual)
        return RowMatch(entries, unmatched.sortedBy { messages.indexOf(it) })
    }

    private fun matchKeyed(message: SettledMessage): Boolean {
        val row = rows.firstOrNull { !it.isTaken() && it.matchesKey(message) } ?: return false
        claim(row, message)
        return true
    }

    private fun matchByText(messages: List<SettledMessage>): List<SettledMessage> =
        messages.groupBy { it.messageType to it.finalText }.values.flatMap { group -> pairGroup(group) }

    private fun pairGroup(group: List<SettledMessage>): List<SettledMessage> {
        val first = group.first()
        val candidates = rows.filter { !it.isTaken() && it.isTextMatch(first) }
        val paired = minOf(group.size, candidates.size)
        candidates.takeLast(paired).zip(group.takeLast(paired)).forEach { (row, message) -> claim(row, message) }
        return group.dropLast(paired)
    }

    private fun claim(row: JsonObject, message: SettledMessage) {
        val ref = row.ref() ?: return
        taken += ref
        entries[ref] = RowIdentity(message.logicalId, turnId)
    }

    private fun JsonObject.isTaken(): Boolean = ref()?.let { it in taken } ?: true

    private fun JsonObject.isTextMatch(message: SettledMessage): Boolean =
        stringField("message_type") == message.messageType && message.finalText != null && messageText() == message.finalText

    private fun JsonObject.matchesKey(message: SettledMessage): Boolean {
        val type = stringField("message_type")
        return when (message.messageType) {
            "user_message" -> type == "user_message" && message.otid != null && stringField("otid") == message.otid
            "tool_call_message", "approval_request_message" ->
                type in TOOL_CALL_ROW_TYPES && canonicalToolCalls(this).any { it.toolCallId == message.toolCallId }
            "tool_return_message" ->
                type == "tool_return_message" && canonicalToolReturn(this).toolCallId == message.toolCallId
            else -> message.upstreamId != null && type == message.messageType && stringField("id") == message.upstreamId
        }
    }
}

internal fun JsonObject.ref(): StoredRowRef? {
    val id = stringField("id") ?: return null
    return StoredRowRef(id, stringField("message_type") ?: return null)
}
