package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.SUBAGENT_ACTIVITY_MAX_LINE_BYTES
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Shared fail-closed projection policy for child-attributed parent stream frames. */
object SubagentParentProjection {
    const val TERMINAL_SUMMARY_MAX_BYTES: Int = 512
    const val ERROR_TAIL_MAX_BYTES: Int = 512

    private val publicActivityTypes = setOf(
        "assistant_message",
        "status_message",
        "progress_message",
        "todo_update",
    )

    fun activityLine(delta: JsonElement): String? {
        val obj = delta as? JsonObject ?: return null
        val type = obj.string("message_type") ?: return null
        if (type !in publicActivityTypes) return null
        val raw = obj.string("summary")
            ?: obj.string("status_text")
            ?: obj.string("active_form")
            ?: obj.string("content")
            ?: return null
        return sanitizeLine(raw)
    }

    fun sanitizedAgentReturn(
        delta: JsonObject,
        conversationId: String,
        messageId: String? = null,
    ): JsonObject {
        val body = delta["tool_return"]?.let(::bodyString)
            ?: delta["output"]?.let(::bodyString)
            ?: delta["result"]?.let(::bodyString)
            ?: ""
        val notification = notificationFields(body)
        val isError = delta.string("status")?.lowercase() in setOf("error", "failed")
        val summary = notification["summary"]
            ?: if (isError) "Sub-agent dispatch failed" else "Sub-agent dispatched"
        val transcript = notification["transcript"] ?: transcriptPointer(body)
        val out = delta.toMutableMap()
        dispatchIdentity(body)?.let { identity ->
            out["subagent_dispatch"] = identity
        }
        val compactResult = dispatchIdentity(body)?.toString()
            ?: summary.takeUtf8Bytes(TERMINAL_SUMMARY_MAX_BYTES)
        out["tool_return"] = JsonPrimitive(compactResult)
        out.remove("output")
        out.remove("result")
        out.remove("stdout")
        out.remove("stderr")
        out.remove("tool_returns")
        out["subagent_dispatch_acknowledged"] = JsonPrimitive(true)
        out["subagent_transcript_pointer"] = buildJsonObject {
            put("method", "tool_return.get")
            put("conversation_id", conversationId)
            messageId?.let { put("message_id", it) }
            transcript?.let { put("uri", it) }
        }
        if (isError) {
            out["subagent_error_tail"] = JsonPrimitive(
                (notification["summary"] ?: "Sub-agent dispatch failed")
                    .takeUtf8Bytes(ERROR_TAIL_MAX_BYTES),
            )
        }
        return JsonObject(out)
    }

    private fun sanitizeLine(raw: String): String? {
        val line = raw.lineSequence().firstOrNull().orEmpty().trim()
        if (line.isBlank()) return null
        val lower = line.lowercase()
        if (lower.startsWith("<") ||
            lower.contains("hidden reasoning") ||
            lower.startsWith("prompt:") ||
            lower.startsWith("context:") ||
            lower.startsWith("arguments:")
        ) return null
        return line.takeUtf8Bytes(SUBAGENT_ACTIVITY_MAX_LINE_BYTES)
    }

    private fun notificationFields(body: String): Map<String, String> {
        if (!body.contains("<task-notification", ignoreCase = true)) return emptyMap()
        return listOf("summary", "transcript").mapNotNull { name ->
            Regex("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)?.let { name to it }
        }.toMap()
    }

    private fun dispatchIdentity(body: String): JsonObject? = runCatching {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
        val taskId = parsed.string("task_id") ?: parsed.string("taskId")
        val agentId = parsed.string("agent_id") ?: parsed.string("agentId")
        val conversationId = parsed.string("conversation_id") ?: parsed.string("conversationId")
        if (taskId == null && agentId == null && conversationId == null) return@runCatching null
        buildJsonObject {
            taskId?.let { put("task_id", it.takeUtf8Bytes(128)) }
            agentId?.let { put("agent_id", it.takeUtf8Bytes(128)) }
            conversationId?.let { put("conversation_id", it.takeUtf8Bytes(128)) }
        }
    }.getOrNull()

    private fun transcriptPointer(body: String): String? = body.lineSequence()
        .firstOrNull { it.contains("Full transcript", ignoreCase = true) }
        ?.substringAfter(':', "")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun bodyString(element: JsonElement): String =
        if (element is JsonPrimitive && element.isString) element.content else element.toString()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        var used = 0
        val out = StringBuilder()
        for (char in this) {
            val size = char.toString().encodeToByteArray().size
            if (used + size > maxBytes) break
            out.append(char)
            used += size
        }
        return out.toString()
    }
}
