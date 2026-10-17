package com.letta.mobile.runtime.local

import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * letta-mobile-by4vm.121.16: OpenAI chat-completions request → [OnDeviceChatRequest], one to one.
 *
 * The bridge is a protocol translator, not a policy layer: every message keeps its role, text,
 * images, tool calls and tool results; `tools` pass through as the OpenAI `function` objects
 * (LiteRT-LM's `OpenApiTool` takes exactly that shape). Which tools an agent gets is decided
 * upstream (the shared agent profile), never here. A request that has no faithful LiteRT-LM
 * equivalent is rejected with the reason ([OnDeviceRequestRejected]), not quietly reshaped.
 */
object OpenAiChatTranslation {
    const val INVALID_REQUEST = "invalid_request"
    const val UNSUPPORTED = "on_device_unsupported_request"

    private val json = LettaJson.wire

    fun translate(request: JsonObject): OnDeviceChatRequest {
        val messages = (request["messages"] as? JsonArray)?.map { it as? JsonObject ?: reject("Every message must be an object.") }
            ?: reject("messages is required.")
        if (messages.isEmpty()) reject("messages is empty.")
        val leadingSystem = messages.takeWhile { it.role() in SYSTEM_ROLES }
        val toolNames = toolNamesById(messages)
        val rest = groupToolResults(messages.drop(leadingSystem.size).map { it.toMessage(toolNames) })
        if (rest.isEmpty()) reject("The request has only system messages; there is nothing to answer.")
        val latest = rest.last()
        if (latest.role != OnDeviceRole.User && latest.role != OnDeviceRole.Tool) {
            reject("The last message must be the user's or a tool result, not ${latest.role.name.lowercase()}.")
        }
        return OnDeviceChatRequest(
            systemInstruction = leadingSystem.map { it.contentText() }.filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { null },
            history = rest.dropLast(1),
            latest = latest,
            tools = toolsOf(request),
        )
    }

    /**
     * The tools LiteRT-LM is offered. `tool_choice` "auto" (or none sent) is LiteRT-LM's own
     * behaviour; "none" means the model may not call any, so none is offered. A forced choice
     * ("required", one named function) has no LiteRT-LM equivalent and is rejected.
     */
    private fun toolsOf(request: JsonObject): List<OnDeviceTool> {
        val tools = (request["tools"] as? JsonArray).orEmpty().map(::toolOf)
        return when (val choice = request["tool_choice"]) {
            null, is JsonPrimitive -> when (choice?.contentOrNull ?: "auto") {
                "auto" -> tools
                "none" -> emptyList()
                else -> throw OnDeviceRequestRejected(
                    UNSUPPORTED,
                    "tool_choice=$choice cannot be enforced by LiteRT-LM; send \"auto\" or \"none\".",
                )
            }
            else -> throw OnDeviceRequestRejected(
                UNSUPPORTED,
                "tool_choice=$choice (a forced function) cannot be enforced by LiteRT-LM; send \"auto\" or \"none\".",
            )
        }
    }

    private fun toolOf(tool: JsonElement): OnDeviceTool {
        val function = (tool as? JsonObject)?.get("function") as? JsonObject
            ?: reject("Every tool must be {\"type\": \"function\", \"function\": {...}}.")
        val name = function.stringField("name") ?: reject("A tool function has no name.")
        return OnDeviceTool(name = name, functionJson = function.toString())
    }

    /** Tool messages name the call they answer by id; LiteRT-LM keys results by tool name. */
    private fun toolNamesById(messages: List<JsonObject>): Map<String, String> =
        messages.flatMap { message ->
            message.toolCallObjects().mapNotNull { call ->
                val id = call.stringField("id") ?: return@mapNotNull null
                val name = (call["function"] as? JsonObject)?.stringField("name") ?: return@mapNotNull null
                id to name
            }
        }.toMap()

    private fun JsonObject.toMessage(toolNames: Map<String, String>): OnDeviceMessage = when (role()) {
        in SYSTEM_ROLES -> OnDeviceMessage(OnDeviceRole.System, contentText())
        "user" -> OnDeviceMessage(OnDeviceRole.User, contentText(), images = images())
        "assistant" -> OnDeviceMessage(OnDeviceRole.Model, contentText(), toolCalls = toolCallObjects().map(::toolCallOf))
        "tool" -> {
            val id = stringField("tool_call_id")
            val name = stringField("name") ?: id?.let(toolNames::get)
                ?: reject("Tool result ${id ?: "(no tool_call_id)"} answers no assistant tool call in this request.")
            OnDeviceMessage(OnDeviceRole.Tool, toolResults = listOf(OnDeviceToolResult(name, contentText())))
        }
        else -> reject("Unsupported message role: ${role()}.")
    }

    /** Results of one round of calls (consecutive tool messages) go back to the model together, as LiteRT-LM sends them. */
    private fun groupToolResults(messages: List<OnDeviceMessage>): List<OnDeviceMessage> =
        messages.fold(mutableListOf()) { grouped, message ->
            val previous = grouped.lastOrNull()
            if (message.role == OnDeviceRole.Tool && previous?.role == OnDeviceRole.Tool) {
                grouped[grouped.lastIndex] = previous.copy(toolResults = previous.toolResults + message.toolResults)
            } else {
                grouped += message
            }
            grouped
        }

    private fun toolCallOf(call: JsonObject): OnDeviceToolCall {
        val function = call["function"] as? JsonObject ?: reject("An assistant tool call has no function.")
        val name = function.stringField("name") ?: reject("An assistant tool call has no name.")
        val arguments = function.stringField("arguments")?.ifBlank { "{}" } ?: "{}"
        if (runCatching { json.parseToJsonElement(arguments) as JsonObject }.isFailure) {
            reject("Tool call $name has arguments that are not a JSON object.")
        }
        return OnDeviceToolCall(id = call.stringField("id"), name = name, argumentsJson = arguments)
    }

    private fun JsonObject.toolCallObjects(): List<JsonObject> =
        (this["tool_calls"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.role(): String = stringField("role") ?: "user"

    private fun reject(message: String): Nothing = throw OnDeviceRequestRejected(INVALID_REQUEST, message)

    private val SYSTEM_ROLES = setOf("system", "developer")
}
