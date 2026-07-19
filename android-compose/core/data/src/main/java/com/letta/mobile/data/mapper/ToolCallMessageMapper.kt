package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import java.time.Duration

internal data class MappedToolCall(val message: UiMessage, val consumedReturnId: String?)

internal data class BaseUiMessageParams(
    val role: String,
    val content: String,
    val toolCalls: List<UiToolCall>? = null,
    val generatedUi: UiGeneratedComponent? = null,
    val attachments: List<UiImageAttachment> = emptyList(),
)

internal fun AppMessage.mapToolCall(
    matchedReturn: AppMessage?,
    foldedApprovals: Map<String, FoldedToolApproval>,
): MappedToolCall {
    val name = toolName
    val returnContent = matchedReturn?.content
    mapGeneratedUiResult(name, returnContent)?.let {
        return MappedToolCall(
            message = baseUiMessage(
                BaseUiMessageParams(
                    role = "assistant",
                    content = it.fallbackText.orEmpty(),
                    generatedUi = it,
                ),
            ),
            consumedReturnId = matchedReturn?.id,
        )
    }
    if (name == "send_message" && returnContent != null) {
        val visibleText = extractSendMessageText(content, returnContent)
        if (visibleText.isNotBlank()) {
            return MappedToolCall(
                message = baseUiMessage(BaseUiMessageParams(role = "assistant", content = visibleText)),
                consumedReturnId = matchedReturn.id,
            )
        }
    }
    val imageAttachments = matchedReturn.imageAttachments()
    val toolCall = UiToolCall(
        name = name ?: "tool",
        arguments = content,
        result = returnContent,
        status = matchedReturn?.toolReturnStatus,
        generatedImageAttachments = if (name == "generate_image") imageAttachments else emptyList(),
        executionTimeMs = matchedReturn?.let { Duration.between(date, it.date).toMillis().takeIf { value -> value >= 0L } },
        toolCallId = toolCallId,
        approvalDecision = toolCallId?.let { foldedApprovals[it]?.decision },
        subagentDispatch = mapSubagentDispatch(name, toolCallId, content, returnContent),
    )
    return MappedToolCall(
        message = baseUiMessage(
            BaseUiMessageParams(
                role = "tool",
                content = "",
                toolCalls = listOf(toolCall),
                attachments = if (name == "generate_image") emptyList() else imageAttachments,
            ),
        ),
        consumedReturnId = matchedReturn?.id,
    )
}

internal fun AppMessage.baseUiMessage(params: BaseUiMessageParams): UiMessage = UiMessage(
    id = id,
    role = params.role,
    content = params.content,
    timestamp = date.toString(),
    runId = runId,
    stepId = stepId,
    toolCalls = params.toolCalls,
    generatedUi = params.generatedUi,
    attachments = params.attachments,
)

internal fun AppMessage?.imageAttachments(): List<UiImageAttachment> =
    this?.attachments.orEmpty().map { UiImageAttachment(it.base64, it.mediaType) }

internal fun mapSubagentDispatch(name: String?, toolCallId: String?, arguments: String, result: String?) =
    if (name == "Agent") extractSubagentDispatch(toolCallId, arguments, result) else null
