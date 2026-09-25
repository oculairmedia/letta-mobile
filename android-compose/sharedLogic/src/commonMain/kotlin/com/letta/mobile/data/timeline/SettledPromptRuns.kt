package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.syntheticHydratedToolRunId
import com.letta.mobile.data.model.UiMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gives a settled reply the run identity its live projection had (letta-mobile-qygvv.20).
 *
 * App Server `message.list` rows carry no `run_id`, so the durable copy of a turn is a user row
 * followed by run-less assistant rows. Without a run the settled reply renders as a plain bubble and
 * the run disclosure ("Worked for 2s") that the live overlay showed vanishes on reconcile. The prompt
 * that opened the turn is durable as well, and it is exactly what bounds the turn, so it names the
 * run. Everything this derives from survives a relaunch: the prompt row and the rows' own dates.
 *
 * Only a segment the page proves complete on its older side (its prompt is on the page) is claimed.
 * Segments that already carry a server run id, or that the render builder groups as a hydrated tool
 * run, keep their existing identity.
 *
 * [this] must be in chronological order.
 */
internal fun List<UiMessage>.withPromptOwnedRunIds(): List<UiMessage> {
    val result = toMutableList()
    var prompt = indexOfFirst { it.role == PROMPT_ROLE }
    while (prompt in result.indices) {
        val end = result.nextPromptAfter(prompt)
        result.claimReplies(prompt, end)
        prompt = end
    }
    return result
}

/** The run id a prompt lends to its otherwise run-less replies. Stable across relaunches. */
internal fun promptOwnedRunId(promptId: String): String = "turn-$promptId"

private fun List<UiMessage>.nextPromptAfter(prompt: Int): Int {
    var index = prompt + 1
    while (index < size && this[index].role != PROMPT_ROLE) index++
    return index
}

private fun MutableList<UiMessage>.claimReplies(prompt: Int, end: Int) {
    if (!subList(prompt + 1, end).isUnownedReply()) return
    val runId = promptOwnedRunId(this[prompt].id)
    for (index in prompt + 1 until end) {
        if (this[index].role == REPLY_ROLE) this[index] = this[index].copy(runId = runId)
    }
}

private fun List<UiMessage>.isUnownedReply(): Boolean {
    val replies = filter { it.role == REPLY_ROLE }
    return replies.isNotEmpty() &&
        replies.all { it.runId.isNullOrBlank() } &&
        syntheticHydratedToolRunId(this) == null
}

/** Content type the canonical writer stores a protocol record under when it has no timeline event. */
internal const val TIMELINE_OPAQUE_MESSAGE_CONTENT_TYPE = "application/vnd.letta.message+json;version=1"

/**
 * A stop_reason or usage frame the ledger kept opaquely. It closes a step, never a turn, so it
 * must not split the run it sits inside (its date ties the reply's, so it can sort between rows).
 */
internal fun TimelineProjectionRecord.isRunMetadata(): Boolean {
    if (!isWholeOpaqueProtocolRow()) return false
    val type = runCatching {
        Json.parseToJsonElement(record.body.decodeToString()).jsonObject["message_type"]?.jsonPrimitive?.content
    }.getOrNull()
    return type in RUN_METADATA_TYPES
}

private fun TimelineProjectionRecord.isWholeOpaqueProtocolRow(): Boolean =
    event == null && record.contentType == TIMELINE_OPAQUE_MESSAGE_CONTENT_TYPE && !record.isPreview

private val RUN_METADATA_TYPES = setOf("stop_reason", "usage_statistics")
private const val PROMPT_ROLE = "user"
private const val REPLY_ROLE = "assistant"
