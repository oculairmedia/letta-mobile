package com.letta.mobile.data.model

import java.time.Instant

/**
 * Application-level message model for UI display.
 * Simplified from the API's LettaMessage types.
 *
 * Lives in jvmAndAndroid (not commonMain): [date] is [java.time.Instant], and
 * paging consumers live on Android/Desktop JVM hosts.
 */
data class AppMessage(
    val id: String,
    val date: Instant,
    val messageType: MessageType,
    val content: String,
    val runId: String? = null,
    val stepId: String? = null,
    val isPending: Boolean = false,
    val localId: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolReturnStatus: ToolReturnMessageStatus? = null,
    val generatedUi: GeneratedUiPayload? = null,
    val approvalRequest: ApprovalRequestPayload? = null,
    val approvalResponse: ApprovalResponsePayload? = null,
    // Inline image parts carried on USER/ASSISTANT/SYSTEM messages whose
    // server-side `content` is a multimodal JSON array. Extracted by the
    // mapper from LettaMessage.attachments so the UI can re-render images
    // after hydration from history. See bead letta-mobile-mge5.24.
    val attachments: List<MessageContentPart.Image> = emptyList(),
)

enum class MessageType {
    USER,
    ASSISTANT,
    REASONING,
    TOOL_CALL,
    TOOL_RETURN,
    APPROVAL_REQUEST,
    APPROVAL_RESPONSE,
}

data class GeneratedUiPayload(
    val component: String,
    val propsJson: String,
    val fallbackText: String? = null,
)

data class ApprovalRequestPayload(
    val requestId: String,
    val toolCalls: List<ApprovalToolCallPayload>,
)

data class ApprovalToolCallPayload(
    val toolCallId: String,
    val name: String,
    val arguments: String,
)

data class ApprovalResponsePayload(
    val requestId: String? = null,
    val approved: Boolean? = null,
    val reason: String? = null,
    val approvals: List<ApprovalDecisionPayload> = emptyList(),
)

sealed interface ToolReturnMessageStatus {
    val wireValue: String

    data object Success : ToolReturnMessageStatus { override val wireValue = "success" }
    data object Error : ToolReturnMessageStatus { override val wireValue = "error" }
    data class Unknown(val raw: String) : ToolReturnMessageStatus { override val wireValue = raw }

    companion object {
        fun fromWire(value: String?): ToolReturnMessageStatus? = value?.let {
            when (it) {
                Success.wireValue -> Success
                Error.wireValue -> Error
                else -> Unknown(it)
            }
        }
    }
}

sealed interface ApprovalDecisionStatus {
    val wireValue: String

    data object Approved : ApprovalDecisionStatus { override val wireValue = "approved" }
    data object Rejected : ApprovalDecisionStatus { override val wireValue = "rejected" }
    data object Pending : ApprovalDecisionStatus { override val wireValue = "pending" }
    data class Unknown(val raw: String) : ApprovalDecisionStatus { override val wireValue = raw }

    companion object {
        fun fromWire(value: String?): ApprovalDecisionStatus? = value?.let {
            when (it) {
                Approved.wireValue -> Approved
                Rejected.wireValue -> Rejected
                Pending.wireValue -> Pending
                else -> Unknown(it)
            }
        }
    }
}

data class ApprovalDecisionPayload(
    val toolCallId: String,
    val approved: Boolean? = null,
    val status: ApprovalDecisionStatus? = null,
    val reason: String? = null,
)
