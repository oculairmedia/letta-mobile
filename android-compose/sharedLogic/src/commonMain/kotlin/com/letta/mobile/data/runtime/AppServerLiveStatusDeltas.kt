package com.letta.mobile.data.runtime

import com.letta.mobile.runtime.CompactionStats
import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * letta-mobile-bzvro.7 / .8 / .10: the typed reading of the `stream_delta` message types that
 * describe the run rather than carry its content: `retry`, `status`, the command lifecycle pairs
 * and 0.33's `approval_classification_end`.
 *
 * Shapes follow letta-code `loop-status-protocol.ts`, `protocol_v2.ts` and
 * `approval-classification-protocol.ts` (0.29.12 through 0.33.6). The compaction pair
 * (`event_message` with `event_type: "compaction"`, then `summary_message`) follows the local
 * backend's `emitCompactionChunks` (letta-mobile-kr39h). Every read is fail-soft: a
 * missing or mistyped field degrades to its default and never throws, because this runs inside the
 * turn collect loop (see the mapper's FAIL-SOFT note, letta-mobile-fkpd4).
 */
internal object AppServerLiveStatusDeltas {
    /** The typed payload for a run-describing [messageType]; null for every other type. */
    fun payloadFor(messageType: String?, delta: JsonObject): RuntimeEventPayload? = when (messageType) {
        "retry" -> retry(delta)
        "status" -> delta.str("message")?.let { RuntimeEventPayload.StatusNotice(message = it, level = delta.str("level") ?: "info") }
        "command_start" -> commandStarted(delta, slash = false)
        "slash_command_start" -> commandStarted(delta, slash = true)
        "command_end" -> commandFinished(delta, slash = false)
        "slash_command_end" -> commandFinished(delta, slash = true)
        "approval_classification_end" -> RuntimeEventPayload.ApprovalClassified(
            autoAllowedToolCallIds = delta.strings("auto_allowed_tool_call_ids"),
            autoDeniedToolCallIds = delta.strings("auto_denied_tool_call_ids"),
            userInputToolCallIds = delta.strings("user_input_tool_call_ids"),
        )
        "event_message" -> compactionStarted(delta)
        "summary_message" -> RuntimeEventPayload.CompactionFinished(
            summary = delta.str("summary").orEmpty(),
            stats = (delta["compaction_stats"] as? JsonObject)?.let(::compactionStats),
        )
        else -> null
    }

    /** Only the compaction event is typed; other `event_message` kinds stay raw frames. */
    private fun compactionStarted(delta: JsonObject): RuntimeEventPayload? {
        if (delta.str("event_type") != COMPACTION_EVENT_TYPE) return null
        val data = delta["event_data"] as? JsonObject
        return RuntimeEventPayload.CompactionStarted(trigger = data?.str("trigger"))
    }

    private fun compactionStats(stats: JsonObject) = CompactionStats(
        trigger = stats.str("trigger"),
        contextTokensBefore = stats.long("context_tokens_before"),
        contextTokensAfter = stats.long("context_tokens_after"),
        contextWindow = stats.long("context_window"),
        messagesCountBefore = stats.long("messages_count_before")?.toInt(),
        messagesCountAfter = stats.long("messages_count_after")?.toInt(),
    )

    private const val COMPACTION_EVENT_TYPE = "compaction"

    private fun retry(delta: JsonObject) = RuntimeEventPayload.RetryNotice(
        message = delta.str("message"),
        reason = delta.str("reason"),
        attempt = delta.long("attempt")?.toInt() ?: 0,
        maxAttempts = delta.long("max_attempts")?.toInt() ?: 0,
        delayMs = delta.long("delay_ms") ?: 0L,
        retryKind = delta.str("retry_kind"),
        provider = delta.str("provider"),
        errorCode = delta.str("error_code"),
    )

    private fun commandStarted(delta: JsonObject, slash: Boolean): RuntimeEventPayload? {
        val id = delta.str("command_id") ?: return null
        return RuntimeEventPayload.CommandStarted(commandId = id, input = delta.str("input").orEmpty(), slash = slash)
    }

    private fun commandFinished(delta: JsonObject, slash: Boolean): RuntimeEventPayload? {
        val id = delta.str("command_id") ?: return null
        return RuntimeEventPayload.CommandFinished(
            commandId = id,
            input = delta.str("input").orEmpty(),
            output = delta.str("output").orEmpty(),
            success = delta.bool("success") ?: true,
            dimOutput = delta.bool("dim_output") ?: false,
            preformatted = delta.bool("preformatted") ?: false,
            slash = slash,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return primitive.content.toLongOrNull()
        return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
