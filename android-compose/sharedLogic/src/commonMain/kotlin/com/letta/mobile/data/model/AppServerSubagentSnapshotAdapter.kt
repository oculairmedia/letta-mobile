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
    val activity: SubagentActivitySnapshot? = null,
)

/**
 * Maps an upstream App Server `SubagentSnapshot` into the mobile [SubagentEntry]
 * projection. Host modules remain binding-only.
 */
data class SubagentParentIdentity(
    val conversationId: String,
    val agentId: String?,
)

object AppServerSubagentSnapshotAdapter {
    private val normalizedStatuses = mapOf(
        "pending" to SubagentStatus.RUNNING,
        "in_progress" to SubagentStatus.RUNNING,
        "running" to SubagentStatus.RUNNING,
        "error" to SubagentStatus.FAILED,
        "failed" to SubagentStatus.FAILED,
        "cancelled" to SubagentStatus.CANCELLED,
        "canceled" to SubagentStatus.CANCELLED,
        "completed" to SubagentStatus.COMPLETED,
        "complete" to SubagentStatus.COMPLETED,
        "done" to SubagentStatus.COMPLETED,
    )

    fun toEntry(
        snapshot: AppServerSubagentSnapshot,
        parent: SubagentParentIdentity,
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
            parentAgentId = snapshot.parentAgentId ?: parent.agentId,
            parentConversationId = snapshot.parentConversationId ?: parent.conversationId,
            startedAt = startedAt(snapshot),
            activity = snapshot.activity?.bounded(),
        )
    }

    fun toEntry(raw: JsonObject, parent: SubagentParentIdentity): SubagentEntry? =
        toEntry(decodeSnapshot(raw), parent)

    private fun decodeSnapshot(raw: JsonObject): AppServerSubagentSnapshot {
        // Strict snake_case decode often "succeeds" while ignoring camelCase keys.
        val decoded = runCatching {
            AppServerProtocol.json.decodeFromJsonElement(AppServerSubagentSnapshot.serializer(), raw)
        }.getOrNull()
        return decoded?.takeIf(::hasIdentity) ?: snapshotFromAliases(raw, decoded)
    }

    private fun snapshotFromAliases(
        raw: JsonObject,
        decoded: AppServerSubagentSnapshot?,
    ): AppServerSubagentSnapshot = AppServerSubagentSnapshot(
        subagentId = alias(raw, decoded?.subagentId, "subagent_id", "subagentId", "id"),
        toolCallId = alias(raw, decoded?.toolCallId, "tool_call_id", "toolCallId"),
        conversationId = alias(
            raw,
            decoded?.conversationId,
            "conversation_id",
            "conversationId",
            "subagent_conversation_id",
        ),
        agentId = alias(raw, decoded?.agentId, "agent_id", "agentId", "subagent_agent_id"),
        description = alias(raw, decoded?.description, "description"),
        subagentType = alias(raw, decoded?.subagentType, "subagent_type", "subagentType"),
        status = alias(raw, decoded?.status, "status"),
        error = alias(raw, decoded?.error, "error"),
        taskId = alias(raw, decoded?.taskId, "task_id", "taskId"),
        parentRunId = alias(raw, decoded?.parentRunId, "parent_run_id", "parentRunId"),
        parentAgentId = alias(raw, decoded?.parentAgentId, "parent_agent_id", "parentAgentId"),
        parentConversationId = alias(
            raw,
            decoded?.parentConversationId,
            "parent_conversation_id",
            "parentConversationId",
        ),
        startTime = listOfNotNull(raw["start_time"], raw["started_at_ms"], decoded?.startTime).firstOrNull(),
        startedAt = alias(raw, decoded?.startedAt, "started_at", "startedAt"),
        activity = listOfNotNull(decodeActivity(raw["activity"]), decoded?.activity).firstOrNull(),
    )

    private fun hasIdentity(snapshot: AppServerSubagentSnapshot?): Boolean {
        if (snapshot == null) return false
        return !snapshot.toolCallId.isNullOrBlank() || !snapshot.subagentId.isNullOrBlank()
    }

    private fun normalizeStatus(rawStatus: String?, error: String?): String {
        if (!error.isNullOrBlank()) return SubagentStatus.FAILED
        val status = rawStatus?.lowercase() ?: "pending"
        return normalizedStatuses[status] ?: status
    }

    private fun startedAt(snapshot: AppServerSubagentSnapshot): String? {
        snapshot.startedAt?.takeIf(String::isNotBlank)?.let { return it }
        val primitive = snapshot.startTime as? JsonPrimitive ?: return null
        val numeric = numericTimestamp(primitive) ?: return primitive.contentOrNull
        return normalizeEpochMillis(numeric).toString()
    }

    private fun numericTimestamp(primitive: JsonPrimitive): Long? =
        listOfNotNull(
            primitive.longOrNull,
            primitive.doubleOrNull?.toLong(),
            primitive.contentOrNull?.toLongOrNull(),
        ).firstOrNull()

    private fun normalizeEpochMillis(timestamp: Long): Long =
        if (timestamp < 1_000_000_000_000L) timestamp * 1_000L else timestamp

    private fun alias(raw: JsonObject, fallback: String?, vararg keys: String): String? =
        listOfNotNull(firstString(raw, *keys), fallback).firstOrNull()

    private fun firstString(raw: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (raw[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        }

    private fun decodeActivity(element: JsonElement?): SubagentActivitySnapshot? = runCatching {
        element ?: return@runCatching null
        AppServerProtocol.json.decodeFromJsonElement<SubagentActivitySnapshot>(element)
    }.getOrNull()
}

fun SubagentActivitySnapshot.bounded(): SubagentActivitySnapshot {
    val normalized = lines.asSequence()
        .map(::sanitizeSubagentActivityLine)
        .filter { it.isNotBlank() }
        .fold(mutableListOf<String>()) { acc, line ->
            if (acc.lastOrNull() != line) acc += line
            acc
        }
    return copy(
        lines = normalized.takeLast(SUBAGENT_ACTIVITY_MAX_LINES),
        truncated = truncated || normalized.size > SUBAGENT_ACTIVITY_MAX_LINES,
    )
}

private fun sanitizeSubagentActivityLine(raw: String): String {
    val singleLine = raw.lineSequence().firstOrNull().orEmpty().trim()
    if (singleLine.isBlank()) return ""
    val lowered = singleLine.lowercase()
    if (isPrivateSubagentLine(lowered)) return ""
    return singleLine.takeUtf8Bytes(SUBAGENT_ACTIVITY_MAX_LINE_BYTES)
}

private fun isPrivateSubagentLine(line: String): Boolean =
    line.startsWith("<") ||
        line.contains("hidden reasoning") ||
        listOf("prompt:", "context:", "arguments:").any(line::startsWith)

private fun String.takeUtf8Bytes(maxBytes: Int): String {
    var used = 0
    var index = 0
    while (index < length) {
        val char = this[index]
        val charCount = if (char.isHighSurrogate() && getOrNull(index + 1)?.isLowSurrogate() == true) 2 else 1
        val bytes = substring(index, index + charCount).encodeToByteArray().size
        if (used + bytes > maxBytes) break
        used += bytes
        index += charCount
    }
    return substring(0, index)
}

const val SUBAGENT_ACTIVITY_MAX_LINES: Int = 4
const val SUBAGENT_ACTIVITY_MAX_LINE_BYTES: Int = 240
