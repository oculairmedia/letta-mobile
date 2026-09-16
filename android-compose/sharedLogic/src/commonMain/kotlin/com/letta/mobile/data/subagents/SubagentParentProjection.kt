package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.SUBAGENT_ACTIVITY_MAX_LINE_BYTES
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Shared fail-closed projection policy for child-attributed parent stream frames. */
object SubagentParentProjection {
    private data class Utf8ByteLimit(val value: Int)

    private data class ProjectionLimits(
        val terminalSummary: Utf8ByteLimit,
        val errorTail: Utf8ByteLimit,
        val identity: Utf8ByteLimit,
    )

    private enum class AgentReturnStatus { DISPATCHED, FAILED }

    private enum class NotificationField(val tag: String) { SUMMARY("summary"), TRANSCRIPT("transcript") }

    private enum class WireField(val key: String) {
        MESSAGE_TYPE("message_type"),
        SUMMARY("summary"),
        STATUS_TEXT("status_text"),
        ACTIVE_FORM("active_form"),
        CONTENT("content"),
        TEXT("text"),
        STATUS("status"),
        TASK_ID("task_id"),
        TASK_ID_CAMEL("taskId"),
        AGENT_ID("agent_id"),
        AGENT_ID_CAMEL("agentId"),
        CONVERSATION_ID("conversation_id"),
        CONVERSATION_ID_CAMEL("conversationId"),
    }

    private val limits = ProjectionLimits(
        terminalSummary = Utf8ByteLimit(512),
        errorTail = Utf8ByteLimit(512),
        identity = Utf8ByteLimit(128),
    )

    private val publicActivityTypes = setOf(
        "assistant_message",
        "status_message",
        "progress_message",
        "todo_update",
    )
    private val resultKeys = listOf("tool_return", "output", "result")
    private val privateResultKeys = listOf("output", "result", "stdout", "stderr", "tool_returns")
    private val errorStatuses = setOf("error", "failed")

    fun activityLine(delta: JsonElement): String? {
        val obj = delta as? JsonObject ?: return null
        val type = obj.string(WireField.MESSAGE_TYPE) ?: return null
        if (type !in publicActivityTypes) return null
        val raw = obj.string(WireField.SUMMARY)
            ?: obj.string(WireField.STATUS_TEXT)
            ?: obj.string(WireField.ACTIVE_FORM)
            ?: obj.stringOrBlocks(WireField.CONTENT)
            ?: return null
        return raw.sanitizedActivityLine()
    }

    fun sanitizedAgentReturn(
        delta: JsonObject,
        conversationId: String,
        messageId: String? = null,
    ): JsonObject = rewriteAgentReturn(
        delta = delta,
        location = AgentReturnLocation(conversationId, messageId),
        projection = parseAgentReturn(delta),
    )

    private fun parseAgentReturn(delta: JsonObject): AgentReturnProjection {
        val content = parseAgentReturnContent(delta)
        return AgentReturnProjection(
            compactResult = content.dispatchIdentity?.toString()
                ?: content.summary().takeUtf8Bytes(limits.terminalSummary),
            dispatchIdentity = content.dispatchIdentity,
            transcript = content.notification.transcript ?: content.body.transcriptPointer(),
            errorTail = content.errorTail(),
        )
    }

    private fun parseAgentReturnContent(delta: JsonObject): AgentReturnContent {
        val body = AgentReturnBody(
            resultKeys.firstNotNullOfOrNull { delta[it]?.bodyString() }.orEmpty(),
        )
        return AgentReturnContent(
            body = body,
            notification = body.notificationFields(),
            dispatchIdentity = body.dispatchIdentity(),
            status = if (delta.string(WireField.STATUS)?.lowercase() in errorStatuses) {
                AgentReturnStatus.FAILED
            } else {
                AgentReturnStatus.DISPATCHED
            },
        )
    }

    private fun AgentReturnContent.summary(): String =
        notification.summary ?: defaultSummary(status)

    private fun AgentReturnContent.errorTail(): String? {
        if (status != AgentReturnStatus.FAILED) return null
        return notification.summary?.takeUtf8Bytes(limits.errorTail) ?: summary()
    }

    private fun rewriteAgentReturn(
        delta: JsonObject,
        location: AgentReturnLocation,
        projection: AgentReturnProjection,
    ): JsonObject {
        val out = delta.toMutableMap()
        projection.dispatchIdentity?.let { out["subagent_dispatch"] = it }
        out["tool_return"] = JsonPrimitive(projection.compactResult)
        privateResultKeys.forEach(out::remove)
        out["subagent_dispatch_acknowledged"] = JsonPrimitive(true)
        out["subagent_transcript_pointer"] = transcriptPointerObject(location, projection.transcript)
        projection.errorTail?.let { out["subagent_error_tail"] = JsonPrimitive(it) }
        return JsonObject(out)
    }

    private fun transcriptPointerObject(
        location: AgentReturnLocation,
        transcript: String?,
    ): JsonObject = buildJsonObject {
        put("method", "tool_return.get")
        put("conversation_id", location.conversationId)
        location.messageId?.let { put("message_id", it) }
        transcript?.let { put("uri", it) }
    }

    private fun defaultSummary(status: AgentReturnStatus): String =
        if (status == AgentReturnStatus.FAILED) "Sub-agent dispatch failed" else "Sub-agent dispatched"

    private data class AgentReturnLocation(
        val conversationId: String,
        val messageId: String?,
    )

    private data class AgentReturnBody(val value: String)

    private data class AgentReturnContent(
        val body: AgentReturnBody,
        val notification: AgentReturnNotification,
        val dispatchIdentity: JsonObject?,
        val status: AgentReturnStatus,
    )

    private data class AgentReturnNotification(
        val summary: String?,
        val transcript: String?,
    )

    private data class AgentReturnProjection(
        val compactResult: String,
        val dispatchIdentity: JsonObject?,
        val transcript: String?,
        val errorTail: String?,
    )

    private fun String.sanitizedActivityLine(): String? {
        val line = lineSequence().firstOrNull().orEmpty().trim()
        if (line.isBlank()) return null
        if (line.lowercase().isPrivateLine()) return null
        return line.takeUtf8Bytes(Utf8ByteLimit(SUBAGENT_ACTIVITY_MAX_LINE_BYTES))
    }

    private fun String.isPrivateLine(): Boolean =
        startsWith("<") ||
            contains("hidden reasoning") ||
            listOf("prompt:", "context:", "arguments:").any(::startsWith)

    private fun AgentReturnBody.notificationFields(): AgentReturnNotification {
        if (!value.contains("<task-notification", ignoreCase = true)) {
            return AgentReturnNotification(summary = null, transcript = null)
        }
        return AgentReturnNotification(
            summary = notificationField(NotificationField.SUMMARY),
            transcript = notificationField(NotificationField.TRANSCRIPT),
        )
    }

    private fun AgentReturnBody.notificationField(field: NotificationField): String? =
        Regex("<${field.tag}(?:\\s[^>]*)?>([\\s\\S]*?)</${field.tag}>", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun AgentReturnBody.dispatchIdentity(): JsonObject? = runCatching {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(value) as? JsonObject ?: return@runCatching null
        val taskId = parsed.string(WireField.TASK_ID) ?: parsed.string(WireField.TASK_ID_CAMEL)
        val agentId = parsed.string(WireField.AGENT_ID) ?: parsed.string(WireField.AGENT_ID_CAMEL)
        val conversationId = parsed.string(WireField.CONVERSATION_ID)
            ?: parsed.string(WireField.CONVERSATION_ID_CAMEL)
        if (listOf(taskId, agentId, conversationId).all { it == null }) return@runCatching null
        buildJsonObject {
            taskId?.let { put("task_id", it.takeUtf8Bytes(limits.identity)) }
            agentId?.let { put("agent_id", it.takeUtf8Bytes(limits.identity)) }
            conversationId?.let { put("conversation_id", it.takeUtf8Bytes(limits.identity)) }
        }
    }.getOrNull()

    private fun AgentReturnBody.transcriptPointer(): String? = value.lineSequence()
        .firstOrNull { it.contains("Full transcript", ignoreCase = true) }
        ?.substringAfter(':', "")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun JsonElement.bodyString(): String =
        if (this is JsonPrimitive && isString) content else toString()

    /**
     * letta-mobile-fkpd4: FAIL-SOFT wire read.
     *
     * `.jsonPrimitive` THROWS on a JsonArray/JsonObject, and this accessor runs
     * over RAW App Server frames where several of these keys are legitimately
     * non-scalar — `content` carries Letta content blocks (an array), and a
     * non-scalar `status` was observed in the m6oa1.6 capture. The throw
     * escaped into [com.letta.mobile.data.runtime.AppServerTurnEngine]'s turn
     * collect loop, which settles the whole parent turn as "Tool execution
     * interrupted by stream error" — so a child that SUCCEEDED surfaced as a
     * failed parent turn.
     *
     * Reading a wire field must never throw: a non-primitive simply is not a
     * string here. [stringOrBlocks] recovers the common content-block case
     * rather than silently dropping the text.
     */
    private fun JsonObject.string(field: WireField): String? =
        (this[field.key] as? JsonPrimitive)?.contentOrNull

    /**
     * As [string], but additionally flattens Letta content blocks
     * (`[{"type":"text","text":"..."}]`) into a single line. Used for activity
     * text, where an array-valued `content` is the normal assistant shape and
     * returning null would drop the subagent's visible progress.
     */
    private fun JsonObject.stringOrBlocks(field: WireField): String? {
        string(field)?.let { return it }
        val blocks = this[field.key] as? JsonArray ?: return null
        return blocks
            .mapNotNull { block -> (block as? JsonObject)?.string(WireField.TEXT) }
            .filter(String::isNotBlank)
            .joinToString(" ")
            .takeIf(String::isNotBlank)
    }

    private fun String.takeUtf8Bytes(limit: Utf8ByteLimit): String {
        var used = 0
        var index = 0
        while (index < length) {
            val char = this[index]
            val charCount = if (char.isHighSurrogate() && getOrNull(index + 1)?.isLowSurrogate() == true) 2 else 1
            val size = substring(index, index + charCount).encodeToByteArray().size
            if (used + size > limit.value) break
            used += size
            index += charCount
        }
        return substring(0, index)
    }
}
