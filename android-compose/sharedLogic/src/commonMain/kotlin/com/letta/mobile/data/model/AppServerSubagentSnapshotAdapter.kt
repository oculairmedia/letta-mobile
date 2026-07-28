package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import com.letta.mobile.data.transport.appserver.AppServerProtocol

/**
 * Exact App Server `SubagentSnapshot` wire shape (snake_case).
 * Shared adapter maps this into mobile [SubagentEntry].
 */
@Serializable
data class AppServerSubagentSnapshot(
    @SerialName("subagent_id") val subagentId: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    val description: String? = null,
    @SerialName("subagent_type") val subagentType: String? = null,
    val status: String? = null,
    val error: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("parent_run_id") val parentRunId: String? = null,
    @SerialName("parent_agent_id") val parentAgentId: String? = null,
    @SerialName("parent_conversation_id") val parentConversationId: String? = null,
    /** Epoch seconds or milliseconds; may also arrive as a string. */
    @SerialName("start_time") val startTime: JsonElement? = null,
    @SerialName("started_at") val startedAt: String? = null,
)

/**
 * Maps an upstream App Server `SubagentSnapshot` into the mobile [SubagentEntry]
 * projection. Host modules remain binding-only.
 */
object AppServerSubagentSnapshotAdapter {
    fun toEntry(
        snapshot: AppServerSubagentSnapshot,
        parentConversationId: String,
        parentAgentId: String?,
    ): SubagentEntry? {
        val identity = snapshot.toolCallId?.takeIf { it.isNotBlank() }
            ?: snapshot.subagentId?.takeIf { it.isNotBlank() }
            ?: return null
        return SubagentEntry(
            toolCallId = identity,
            description = snapshot.description.orEmpty(),
            subagentType = snapshot.subagentType.orEmpty(),
            status = normalizeStatus(snapshot.status, snapshot.error),
            taskId = snapshot.taskId,
            subagentAgentId = snapshot.agentId,
            subagentConversationId = snapshot.conversationId,
            parentRunId = snapshot.parentRunId,
            parentAgentId = snapshot.parentAgentId ?: parentAgentId,
            parentConversationId = snapshot.parentConversationId ?: parentConversationId,
            startedAt = startedAt(snapshot),
        )
    }

    fun toEntry(
        raw: JsonObject,
        parentConversationId: String,
        parentAgentId: String?,
    ): SubagentEntry? {
        // Strict snake_case decode often "succeeds" while ignoring camelCase keys
        // (unknown keys). Prefer that result only when identity fields are present;
        // otherwise rebuild from snake_case + camelCase aliases.
        val decoded = runCatching {
            AppServerProtocol.json.decodeFromJsonElement(AppServerSubagentSnapshot.serializer(), raw)
        }.getOrNull()
        val snapshot = if (hasIdentity(decoded)) {
            decoded!!
        } else {
            AppServerSubagentSnapshot(
                subagentId = firstString(raw, "subagent_id", "subagentId", "id")
                    ?: decoded?.subagentId,
                toolCallId = firstString(raw, "tool_call_id", "toolCallId")
                    ?: decoded?.toolCallId,
                conversationId = firstString(raw, "conversation_id", "conversationId", "subagent_conversation_id")
                    ?: decoded?.conversationId,
                agentId = firstString(raw, "agent_id", "agentId", "subagent_agent_id")
                    ?: decoded?.agentId,
                description = firstString(raw, "description") ?: decoded?.description,
                subagentType = firstString(raw, "subagent_type", "subagentType")
                    ?: decoded?.subagentType,
                status = firstString(raw, "status") ?: decoded?.status,
                error = firstString(raw, "error") ?: decoded?.error,
                taskId = firstString(raw, "task_id", "taskId") ?: decoded?.taskId,
                parentRunId = firstString(raw, "parent_run_id", "parentRunId")
                    ?: decoded?.parentRunId,
                parentAgentId = firstString(raw, "parent_agent_id", "parentAgentId")
                    ?: decoded?.parentAgentId,
                parentConversationId = firstString(raw, "parent_conversation_id", "parentConversationId")
                    ?: decoded?.parentConversationId,
                startTime = raw["start_time"] ?: raw["started_at_ms"] ?: decoded?.startTime,
                startedAt = firstString(raw, "started_at", "startedAt") ?: decoded?.startedAt,
            )
        }
        return toEntry(snapshot, parentConversationId, parentAgentId)
    }

    private fun hasIdentity(snapshot: AppServerSubagentSnapshot?): Boolean {
        if (snapshot == null) return false
        return !snapshot.toolCallId.isNullOrBlank() || !snapshot.subagentId.isNullOrBlank()
    }

    private fun normalizeStatus(rawStatus: String?, error: String?): String {
        val status = rawStatus?.lowercase()
        return when {
            !error.isNullOrBlank() -> SubagentStatus.FAILED
            status == null || status == "pending" || status == "in_progress" ->
                SubagentStatus.RUNNING
            status == "error" || status == "failed" -> SubagentStatus.FAILED
            status == "cancelled" || status == "canceled" -> SubagentStatus.CANCELLED
            status == "completed" || status == "complete" || status == "done" ->
                SubagentStatus.COMPLETED
            status == "running" -> SubagentStatus.RUNNING
            else -> status
        }
    }

    private fun startedAt(snapshot: AppServerSubagentSnapshot): String? {
        snapshot.startedAt?.takeIf { it.isNotBlank() }?.let { return it }
        val element = snapshot.startTime ?: return null
        val primitive = element as? JsonPrimitive ?: return null
        val numeric = primitive.longOrNull
            ?: primitive.doubleOrNull?.toLong()
            ?: primitive.contentOrNull?.toLongOrNull()
            ?: return primitive.contentOrNull
        val epochMs = if (numeric < 1_000_000_000_000L) numeric * 1_000L else numeric
        return epochMs.toString()
    }

    private fun firstString(raw: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (raw[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
}
