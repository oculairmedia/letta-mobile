package com.letta.mobile.data.model

import com.letta.mobile.data.transport.appserver.AppServerProtocol
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 *  (RC5): progress read from letta-code's native
 * `update_subagent_state.subagents[].tool_calls` (`[{id, name, args}]`).
 *
 * The tool-call list is the only progress signal letta-code sends for a
 * running subagent, so it becomes two things the UI can use honestly: a count
 * (how much work happened) and a bounded activity tail (the latest tool calls,
 * one short line each). Arguments are reduced to one well-known primary field
 * (a path, a command, a pattern) and never copied whole, so prompts and
 * payloads do not reach the parent's surfaces.
 */
internal data class SubagentNativeProgress(
    val toolCallCount: Int?,
    val activity: SubagentActivitySnapshot?,
)

internal object SubagentNativeProgressParser {
    private const val MAX_PRIMARY_CHARS = 72
    private val PRIMARY_ARG_KEYS = listOf(
        "description", "file_path", "path", "command", "pattern", "query", "url", "skill", "name",
    )

    fun parse(toolCalls: JsonElement?): SubagentNativeProgress {
        val calls = toolCalls as? JsonArray ?: return SubagentNativeProgress(toolCallCount = null, activity = null)
        val lines = calls.mapNotNull { (it as? JsonObject)?.let(::line) }
        val activity = lines.takeIf { it.isNotEmpty() }?.let { SubagentActivitySnapshot(lines = it).bounded() }
        return SubagentNativeProgress(toolCallCount = calls.size, activity = activity)
    }

    private fun line(call: JsonObject): String? {
        val name = (call["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val primary = primaryArgument(call["args"] ?: call["arguments"]) ?: return name
        return "$name $primary"
    }

    private fun primaryArgument(args: JsonElement?): String? {
        val obj = when (args) {
            is JsonObject -> args
            is JsonPrimitive -> args.contentOrNull?.let(::parseObject)
            else -> null
        } ?: return null
        val value = PRIMARY_ARG_KEYS.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        } ?: return null
        return if (value.length <= MAX_PRIMARY_CHARS) value else value.take(MAX_PRIMARY_CHARS - 1) + "…"
    }

    private fun parseObject(text: String): JsonObject? = runCatching {
        AppServerProtocol.json.parseToJsonElement(text) as? JsonObject
    }.getOrNull()
}

private const val AGENT_ID_PREFIX = "agent-"
private val TOOL_CALLS_KEYS = listOf("tool_calls", "toolCalls")
private val TOTAL_TOKENS_KEYS = listOf("total_tokens", "totalTokens")
private val DURATION_KEYS = listOf("duration_ms", "durationMs")
private val MODEL_KEYS = listOf("model")
private val BACKGROUND_KEYS = listOf("is_background", "isBackground")

/**
 *  (RC5): the native progress fields read leniently (snake or
 * camel case), for snapshots the strict decode could not identify.
 */
internal fun AppServerSubagentSnapshot.withNativeAliases(
    raw: JsonObject,
    decoded: AppServerSubagentSnapshot?,
): AppServerSubagentSnapshot = copy(
    toolCalls = raw.first(TOOL_CALLS_KEYS) ?: decoded?.toolCalls,
    totalTokens = raw.firstPrimitive(TOTAL_TOKENS_KEYS)?.longValue() ?: decoded?.totalTokens,
    durationMs = raw.firstPrimitive(DURATION_KEYS)?.longValue() ?: decoded?.durationMs,
    model = raw.firstPrimitive(MODEL_KEYS)?.contentOrNull?.takeIf { it.isNotBlank() } ?: decoded?.model,
    isBackground = raw.firstPrimitive(BACKGROUND_KEYS)?.booleanOrNull ?: decoded?.isBackground,
)

/**
 * The child agent: `agent_id` when a producer sends it, else the id inside
 * letta-code's `agent_url` (a bare local agent id, or a chat URL ending in one).
 */
internal fun AppServerSubagentSnapshot.childAgentId(): String? =
    agentId?.takeIf { it.isNotBlank() }
        ?: agentUrl?.trim()?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/')
            ?.takeIf { it.startsWith(AGENT_ID_PREFIX) }

private fun JsonObject.first(keys: List<String>): JsonElement? = keys.firstNotNullOfOrNull { this[it] }

private fun JsonObject.firstPrimitive(keys: List<String>): JsonPrimitive? =
    keys.firstNotNullOfOrNull { this[it] as? JsonPrimitive }

private fun JsonPrimitive.longValue(): Long? = longOrNull ?: doubleOrNull?.toLong() ?: contentOrNull?.toLongOrNull()
