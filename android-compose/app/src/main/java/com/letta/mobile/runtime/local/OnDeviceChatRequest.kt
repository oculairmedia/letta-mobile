package com.letta.mobile.runtime.local

/**
 * letta-mobile-by4vm.121.16: one OpenAI chat-completions request, translated faithfully for the
 * on-device engine. Nothing is dropped, shortened or rewritten here: every message, image, tool
 * schema and tool call letta-code sent reaches the engine, which maps it onto LiteRT-LM's own
 * conversation API (system instruction, history, tools, the new message).
 */
data class OnDeviceChatRequest(
    /** The leading system message(s), in order, joined by a blank line; null when there is none. */
    val systemInstruction: String?,
    /** Every message before [latest], in order. */
    val history: List<OnDeviceMessage>,
    /** The message the model answers: the user's turn, or the results of its last tool calls. */
    val latest: OnDeviceMessage,
    /** The tools offered, each the OpenAI `function` object verbatim. */
    val tools: List<OnDeviceTool> = emptyList(),
) {
    val images: List<OnDeviceImage> get() = (history + latest).flatMap { it.images }

    companion object {
        /** A one-turn plain-text request (the debug self-test and the device test). */
        fun userText(text: String): OnDeviceChatRequest =
            OnDeviceChatRequest(systemInstruction = null, history = emptyList(), latest = OnDeviceMessage.user(text))
    }
}

enum class OnDeviceRole { System, User, Model, Tool }

/** One conversation message; a [OnDeviceRole.Tool] message carries one result per tool call it answers. */
data class OnDeviceMessage(
    val role: OnDeviceRole,
    val text: String = "",
    val images: List<OnDeviceImage> = emptyList(),
    val toolCalls: List<OnDeviceToolCall> = emptyList(),
    val toolResults: List<OnDeviceToolResult> = emptyList(),
) {
    companion object {
        fun user(text: String): OnDeviceMessage = OnDeviceMessage(OnDeviceRole.User, text)
    }
}

/** A tool call: OpenAI's `id` (null for one the engine just produced), the tool's name, its arguments as JSON. */
data class OnDeviceToolCall(val id: String?, val name: String, val argumentsJson: String)

/** A tool's result: the call it answers (by name, as LiteRT-LM keys results) and the result text. */
data class OnDeviceToolResult(val name: String, val content: String)

/** A tool offered to the model: its name and the OpenAI `function` object (name, description, parameters) as JSON. */
data class OnDeviceTool(val name: String, val functionJson: String)

/** What the engine answered: text, tool calls, or both. */
data class OnDeviceReply(val text: String, val toolCalls: List<OnDeviceToolCall> = emptyList())

/**
 * A request the engine cannot run as sent, answered with a non-retryable 422 naming why (a context
 * overflow, more images than the engine takes, a vision encoder the device cannot load, an OpenAI
 * feature LiteRT-LM has no equivalent for). Never a silent trim: the caller sees the reason.
 */
class OnDeviceRequestRejected(val code: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause)
