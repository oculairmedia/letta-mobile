package com.letta.mobile.data.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Maps an upstream App Server `SubagentSnapshot` (snake_case wire) into the
 * mobile [SubagentEntry] projection. Host modules remain binding-only.
 */
object AppServerSubagentSnapshotAdapter {
    fun toEntry(
        raw: JsonObject,
        parentConversationId: String,
        parentAgentId: String?,
    ): SubagentEntry? {
        val toolCallId = firstString(
            raw,
            "tool_call_id",
            "toolCallId",
            "subagent_id",
            "id",
        ) ?: return null
        return SubagentEntry(
            toolCallId = toolCallId,
            description = firstString(raw, "description").orEmpty(),
            subagentType = firstString(raw, "subagent_type", "subagentType").orEmpty(),
            status = normalizeStatus(raw),
            taskId = firstString(raw, "task_id", "taskId"),
            subagentAgentId = firstString(
                raw,
                "subagent_agent_id",
                "subagentAgentId",
                "agent_id",
            ),
            subagentConversationId = firstString(
                raw,
                "subagent_conversation_id",
                "subagentConversationId",
                "conversation_id",
            ),
            parentRunId = firstString(raw, "parent_run_id", "parentRunId"),
            parentAgentId = firstString(raw, "parent_agent_id", "parentAgentId")
                ?: parentAgentId,
            parentConversationId = firstString(
                raw,
                "parent_conversation_id",
                "parentConversationId",
            ) ?: parentConversationId,
            startedAt = startedAt(raw),
        )
    }

    private fun normalizeStatus(raw: JsonObject): String {
        val rawStatus = firstString(raw, "status")?.lowercase()
        val error = firstString(raw, "error")
        return when {
            !error.isNullOrBlank() -> SubagentStatus.FAILED
            rawStatus == null || rawStatus == "pending" || rawStatus == "in_progress" ->
                SubagentStatus.RUNNING
            rawStatus == "error" || rawStatus == "failed" -> SubagentStatus.FAILED
            rawStatus == "cancelled" || rawStatus == "canceled" -> SubagentStatus.CANCELLED
            rawStatus == "completed" || rawStatus == "complete" || rawStatus == "done" ->
                SubagentStatus.COMPLETED
            else -> rawStatus
        }
    }

    private fun startedAt(raw: JsonObject): String? {
        firstString(raw, "started_at", "startedAt")?.let { return it }
        val numeric = firstLong(raw, "start_time", "started_at_ms", "startedAtMs") ?: return null
        // Upstream may send epoch seconds or milliseconds.
        val epochMs = if (numeric < 1_000_000_000_000L) numeric * 1_000L else numeric
        return epochMs.toString()
    }

    private fun firstString(raw: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            raw[key].asPrimitive()?.contentOrNull?.takeIf { it.isNotBlank() }
        }

    private fun firstLong(raw: JsonObject, vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            raw[key].asPrimitive()?.longOrNull
                ?: raw[key].asPrimitive()?.contentOrNull?.toLongOrNull()
        }

    private fun JsonElement?.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive
}
