package com.letta.mobile.runtime.local

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool

/**
 * letta-mobile-by4vm.121.16: [OnDeviceChatRequest] onto LiteRT-LM's native conversation API.
 *
 * - the system instruction is the conversation's `systemInstruction`;
 * - every earlier message is an `initialMessages` entry with its own role: user text and images,
 *   model text and `toolCalls`, tool results as `Content.ToolResponse`;
 * - each OpenAI tool is an [OpenApiTool] whose description is the OpenAI `function` object, the
 *   JSON-schema shape LiteRT-LM's tool API takes;
 * - `automaticToolCalling = false`: LiteRT-LM hands the model's tool calls back instead of running
 *   them, and letta-code (the caller) runs them and sends the results in its next request.
 *
 * The model's chat template (inside the `.litertlm` file) renders all of this; nothing is
 * flattened into a hand-made transcript.
 */
internal object LiteRtLmConversationMapping {
    fun config(request: OnDeviceChatRequest, maxOutputTokens: Int): ConversationConfig = ConversationConfig(
        systemInstruction = request.systemInstruction?.let { Contents.of(sanitizeForLiteRt(it)) },
        initialMessages = request.history.map(::message),
        tools = request.tools.map { tool(BridgedOpenApiTool(it)) },
        automaticToolCalling = false,
        maxOutputToken = maxOutputTokens,
    )

    fun message(message: OnDeviceMessage): Message = when (message.role) {
        OnDeviceRole.System -> Message.system(contents(message))
        OnDeviceRole.User -> Message.user(contents(message))
        OnDeviceRole.Model -> Message.model(contents(message), message.toolCalls.map(::toolCall), emptyMap())
        OnDeviceRole.Tool -> Message.tool(
            Contents.of(message.toolResults.map { Content.ToolResponse(it.name, sanitizeForLiteRt(it.content)) }),
        )
    }

    /** Images first, then the text, as LiteRT-LM's vision models expect them. */
    private fun contents(message: OnDeviceMessage): Contents = Contents.of(
        message.images.map { Content.ImageBytes(it.bytes) } +
            listOfNotNull(message.text.takeIf { it.isNotEmpty() }?.let { Content.Text(sanitizeForLiteRt(it)) }),
    )

    private fun toolCall(call: OnDeviceToolCall): ToolCall =
        ToolCall(call.name, ToolArgumentsJson.toArguments(sanitizeForLiteRt(call.argumentsJson)))

    /** The model's streamed messages as one reply: their text in order, and every tool call. */
    fun reply(text: String, toolCalls: List<ToolCall>): OnDeviceReply = OnDeviceReply(
        text = text,
        toolCalls = toolCalls.map { OnDeviceToolCall(id = null, name = it.name, argumentsJson = ToolArgumentsJson.toJson(it.arguments)) },
    )

    /** The text parts of one streamed message (a tool-call message has none). */
    fun textOf(message: Message): String =
        message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    /**
     * An OpenAI tool offered to LiteRT-LM. With automatic tool calling off LiteRT-LM never runs
     * it: letta-code executes the call. [execute] answering means that contract broke.
     */
    private class BridgedOpenApiTool(private val tool: OnDeviceTool) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = tool.functionJson

        override fun execute(paramsJsonString: String): String =
            error("LiteRT-LM tried to run ${tool.name} itself; the bridge runs with automaticToolCalling=false.")
    }
}

