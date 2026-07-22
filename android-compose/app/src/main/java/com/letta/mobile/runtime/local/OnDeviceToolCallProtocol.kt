package com.letta.mobile.runtime.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Base64
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Prompt-format tool calling for on-device models (letta-mobile-69i0z).
 *
 * LiteRT-LM exposes plain text generation only, so OpenAI `tools` schemas are
 * rendered into the prompt and tool calls are parsed back out of the model's
 * text by convention: the model replies with a single fenced block
 *
 * ```tool_call
 * {"name": "<tool>", "arguments": { ... }}
 * ```
 *
 * The bridge translates that to OpenAI-shaped `tool_calls` so letta.js's
 * pi-ai provider (and its in-process tool executor) work unchanged.
 */
object OnDeviceToolCallProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface ModelTurn {
        data class Text(val text: String) : ModelTurn

        data class ToolCall(
            val name: String,
            val argumentsJson: String,
            val leadingText: String,
        ) : ModelTurn
    }

    /** Renders a compact bounded prompt: tool instructions + recent conversation transcript. */
    fun renderPrompt(request: JsonObject): String {
        val parts = mutableListOf(
            "You are a concise assistant. Answer the user's latest message in natural language. Do not repeat a single character, syllable, or word. If unsure, say so briefly."
        )
        val tools = request["tools"] as? JsonArray
        if (!tools.isNullOrEmpty()) {
            parts += renderToolInstructions(tools)
        }
        val messages = (request["messages"] as? JsonArray)
            ?.mapNotNull(::renderMessage)
            .orEmpty()
        parts += fitRecentMessages(parts.sumOf { it.length + 2 }, messages)
        return parts.joinToString("\n\n")
            .trimEnd()
            .fitPromptBudget()
    }

    private fun renderMessage(message: JsonElement): String? {
        val item = message as? JsonObject ?: return null
        val role = item.stringField("role") ?: "user"
        return when (role) {
            "tool" -> {
                val id = item.stringField("tool_call_id").orEmpty()
                "tool result${if (id.isNotBlank()) " ($id)" else ""}: ${item.contentText().ellipsize(MAX_MESSAGE_CHARS)}"
            }
            "assistant" -> buildString {
                val text = item.contentText()
                if (text.isNotBlank()) appendLine("assistant: ${text.ellipsize(MAX_MESSAGE_CHARS)}")
                (item["tool_calls"] as? JsonArray)?.forEach { call ->
                    val function = (call as? JsonObject)?.get("function") as? JsonObject ?: return@forEach
                    val id = (call["id"] as? JsonPrimitive)?.content.orEmpty()
                    appendLine(
                        "assistant called tool${if (id.isNotBlank()) " ($id)" else ""}: " +
                            "{\"name\": ${JsonPrimitive(function.stringField("name").orEmpty())}, " +
                            "\"arguments\": ${(function.stringField("arguments") ?: "{}").ellipsize(MAX_TOOL_ARGUMENT_CHARS)}}"
                    )
                }
            }.trimEnd().takeIf { it.isNotBlank() }
            else -> "$role: ${item.contentText().ellipsize(MAX_MESSAGE_CHARS)}"
        }
    }

    private fun fitRecentMessages(prefixChars: Int, messages: List<String>): List<String> {
        val selected = ArrayDeque<String>()
        var total = prefixChars
        for (message in messages.asReversed()) {
            val extra = message.length + 2
            if (selected.isNotEmpty() && total + extra > MAX_RENDERED_PROMPT_CHARS) break
            selected.addFirst(message)
            total += extra
        }
        if (selected.size < messages.size && selected.isNotEmpty()) {
            val marker = "[Older conversation context omitted for on-device model context window.]"
            while (selected.isNotEmpty() && total + marker.length + 2 > MAX_RENDERED_PROMPT_CHARS) {
                total -= selected.removeFirst().length + 2
            }
            selected.addFirst(marker)
        }
        return selected.toList()
    }

    private fun String.fitPromptBudget(): String =
        if (length <= MAX_RENDERED_PROMPT_CHARS) this
        else "[Prompt truncated for on-device model context window.]\n" + takeLast(MAX_RENDERED_PROMPT_CHARS - 55)

    private fun renderToolInstructions(tools: JsonArray): String = buildString {
        appendLine("You can use tools. To use a tool, reply with ONLY one block in exactly this format and nothing else:")
        appendLine("```tool_call")
        appendLine("{\"name\": \"<tool name>\", \"arguments\": {<arguments object>}}")
        appendLine("```")
        appendLine("If no tool is needed, reply with plain text and never use the tool_call format.")
        appendLine()
        appendLine("Available tools:")
        tools.forEach { tool ->
            val function = (tool as? JsonObject)?.get("function") as? JsonObject ?: return@forEach
            val name = function.stringField("name") ?: return@forEach
            val description = function.stringField("description").orEmpty()
                .lineSequence().firstOrNull().orEmpty()
            appendLine("- $name: $description")
        }
    }.trimEnd()



    fun extractImages(request: JsonObject): List<OnDeviceImage> =
        (request["messages"] as? JsonArray)
            ?.flatMap { message ->
                ((message as? JsonObject)?.get("content") as? JsonArray)
                    ?.mapNotNull(::imageFromContentPart)
                    .orEmpty()
            }
            .orEmpty()

    private fun imageFromContentPart(part: JsonElement): OnDeviceImage? {
        val item = part as? JsonObject ?: return null
        if (item.stringField("type") != "image_url") return null
        val url = when (val imageUrl = item["image_url"]) {
            is JsonPrimitive -> imageUrl.contentOrNull
            is JsonObject -> imageUrl.stringField("url")
            else -> null
        } ?: return null
        return decodeOpenAiImageUrl(url)
    }

    fun decodeOpenAiImageUrl(url: String): OnDeviceImage? {
        val trimmed = url.trim()
        val dataPrefix = "data:"
        val base64Marker = ";base64,"
        return if (trimmed.startsWith(dataPrefix, ignoreCase = true)) {
            val markerIndex = trimmed.indexOf(base64Marker, ignoreCase = true)
            if (markerIndex < 0) return null
            val mediaType = trimmed.substring(dataPrefix.length, markerIndex).takeIf { it.isNotBlank() }
            val payload = trimmed.substring(markerIndex + base64Marker.length)
            decodeBase64Image(payload, mediaType)
        } else {
            decodeBase64Image(trimmed, mediaType = null)
        }
    }

    private fun decodeBase64Image(payload: String, mediaType: String?): OnDeviceImage? =
        runCatching { OnDeviceImage(Base64.getDecoder().decode(payload), mediaType) }.getOrNull()

    /**
     * Parses a model reply: a `tool_call` fenced block (or a bare JSON object
     * with name+arguments as the whole reply) becomes [ModelTurn.ToolCall];
     * anything else is [ModelTurn.Text].
     */
    fun parseModelOutput(raw: String): ModelTurn {
        val text = raw.trim()
        fencedToolCall(text)?.let { return it }
        bareJsonToolCall(text)?.let { return it }
        return ModelTurn.Text(raw)
    }

    private fun fencedToolCall(text: String): ModelTurn.ToolCall? {
        val match = FENCE_REGEX.find(text) ?: return null
        val payload = parseToolCallObject(match.groupValues[1].trim()) ?: return null
        return payload.copy(leadingText = text.substring(0, match.range.first).trim())
    }

    private fun bareJsonToolCall(text: String): ModelTurn.ToolCall? {
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        return parseToolCallObject(text)
    }

    private fun parseToolCallObject(candidate: String): ModelTurn.ToolCall? {
        val parsed = runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull() ?: return null
        val name = parsed.stringField("name") ?: return null
        val arguments = parsed["arguments"] ?: JsonObject(emptyMap())
        val argumentsJson = when (arguments) {
            is JsonObject -> arguments.toString()
            is JsonPrimitive -> arguments.content.takeIf {
                runCatching { json.parseToJsonElement(it) }.isSuccess
            } ?: return null
            else -> return null
        }
        return ModelTurn.ToolCall(name = name, argumentsJson = argumentsJson, leadingText = "")
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** OpenAI message `content` can be a string or an array of typed parts. */
    private fun JsonObject.contentText(): String = when (val content = this["content"]) {
        null -> ""
        is JsonPrimitive -> content.contentOrEmpty()
        is JsonArray -> content.joinToString("\n") { part ->
            (part as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrEmpty() ?: ""
        }
        else -> content.toString()
    }

    private fun JsonPrimitive.contentOrEmpty(): String = content

    private fun String.ellipsize(maxChars: Int): String =
        if (length <= maxChars) this else take(maxChars) + "…"

    private const val MAX_RENDERED_PROMPT_CHARS = 8_000
    private const val MAX_MESSAGE_CHARS = 1_000
    private const val MAX_TOOL_ARGUMENT_CHARS = 1_000

    // Tolerates ```tool_call and ```json fences; gemma-class models drift.
    private val FENCE_REGEX = Regex(
        "```(?:tool_call|json)?\\s*\\n(\\{[\\s\\S]*?\\})\\s*\\n?```",
    )
}
