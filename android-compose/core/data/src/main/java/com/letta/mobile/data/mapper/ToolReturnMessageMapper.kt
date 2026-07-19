package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiToolCall

internal fun AppMessage.mapUnmatchedToolReturn(
    foldedApprovals: Map<String, FoldedToolApproval>,
): com.letta.mobile.data.model.UiMessage {
    val name = toolName ?: "tool"
    mapGeneratedUiResult(name, content)?.let {
        return baseUiMessage(
            BaseUiMessageParams(
                role = "assistant",
                content = it.fallbackText.orEmpty(),
                generatedUi = it,
            ),
        )
    }
    if (name == "send_message" && content.isNotBlank()) {
        return baseUiMessage(BaseUiMessageParams(role = "assistant", content = content))
    }
    val imageAttachments = this.imageAttachments()
    return baseUiMessage(
        BaseUiMessageParams(
            role = "tool",
            content = "",
            toolCalls = listOf(
                UiToolCall(
                    name = name,
                    arguments = "",
                    result = content.ifBlank { null },
                    status = toolReturnStatus,
                    generatedImageAttachments = imageAttachments,
                    toolCallId = toolCallId,
                    approvalDecision = toolCallId?.let { foldedApprovals[it]?.decision },
                    subagentDispatch = if (name == "Agent") {
                        extractSubagentDispatch(toolCallId, "", content)
                    } else {
                        null
                    },
                ),
            ),
            attachments = if (name == "generate_image") emptyList() else imageAttachments,
        ),
    )
}

internal fun mapGeneratedUiResult(name: String?, result: String?): UiGeneratedComponent? {
    if (name !in generatedUiToolNames || result == null) return null
    val payload = extractGeneratedUiFromString(result) ?: return null
    return UiGeneratedComponent(
        name = payload.component,
        propsJson = payload.propsJson,
        fallbackText = payload.fallbackText,
    )
}
