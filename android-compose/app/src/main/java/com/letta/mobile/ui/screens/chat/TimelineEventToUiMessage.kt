package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.Role
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType

/**
 * Pure mapping from a [TimelineEvent] to the [UiMessage] the chat screen
 * renders. Extracted as a top-level function so it can be unit-tested without
 * instantiating [AdminChatViewModel].
 *
 * Contract (letta-mobile-mge5.23):
 * - Standalone TOOL_RETURN events → null (should be merged into TOOL_CALL).
 * - TOOL_CALL events produce a UiMessage with populated `toolCalls` carrying
 *   name, arguments, result (from [TimelineEvent.Confirmed.toolReturnContent])
 *   and status.
 * - An ApprovalRequest that hasn't been decided produces `approvalRequest`
 *   with approve/reject buttons; once decided, it produces `approvalResponse`
 *   and clears `approvalRequest`.
 * - REASONING produces role="assistant" + isReasoning=true.
 */
internal fun timelineEventToUiMessage(ev: TimelineEvent): UiMessage? {
    return when (ev) {
        is TimelineEvent.Local -> UiMessage(
            id = ev.otid,
            role = when (ev.role) {
                Role.USER -> "user"
                Role.ASSISTANT -> "assistant"
                Role.SYSTEM -> "system"
            },
            content = ev.content,
            timestamp = ev.sentAt.toString(),
            isPending = ev.deliveryState == DeliveryState.SENDING,
            attachments = ev.attachments.map {
                UiImageAttachment(base64 = it.base64, mediaType = it.mediaType)
            },
        )
        is TimelineEvent.Confirmed -> {
            val role = when (ev.messageType) {
                TimelineMessageType.USER -> "user"
                TimelineMessageType.ASSISTANT -> "assistant"
                TimelineMessageType.REASONING -> "assistant"
                TimelineMessageType.SYSTEM -> "system"
                TimelineMessageType.TOOL_CALL -> "assistant"
                TimelineMessageType.TOOL_RETURN -> return null
                TimelineMessageType.OTHER -> return null
            }
            // letta-mobile-23h5 (regression fix 2026-04-19): when an approval
            // has been decided we MUST NOT synthesize a `UiApprovalResponse`
            // alongside the tool calls on the same UiMessage — the renderer
            // (ChatMessageBubble) short-circuits to `ApprovalResponseCard` as
            // soon as `approvalResponse != null` and never paints the rich
            // tool card. Convey the approved state via the per-tool-call
            // `approvalDecision` chip (which `ToolCallCard` already renders),
            // and only emit `UiApprovalRequest` while still pending.
            val uiToolCalls: List<UiToolCall>? =
                if (ev.toolCalls.isNotEmpty()) {
                    val chip: UiToolApprovalDecision? =
                        if (ev.approvalDecided && ev.approvalRequestId != null) {
                            UiToolApprovalDecision.Approved
                        } else null
                    ev.toolCalls.mapIndexed { index, tc ->
                        UiToolCall(
                            name = tc.name ?: "tool",
                            arguments = tc.arguments ?: "",
                            // Attach the return body to the first call —
                            // servers almost always return one result per
                            // batched-tools message, linked to the first id.
                            result = if (index == 0) ev.toolReturnContent else null,
                            status = if (ev.toolReturnContent == null) null
                                else if (ev.toolReturnIsError) "error" else "success",
                            approvalDecision = chip,
                        )
                    }
                } else null
            val uiApproval: UiApprovalRequest? =
                if (!ev.approvalDecided) {
                    ev.approvalRequestId?.let { reqId ->
                        UiApprovalRequest(
                            requestId = reqId,
                            toolCalls = ev.toolCalls.map { tc ->
                                UiApprovalToolCall(
                                    toolCallId = tc.effectiveId,
                                    name = tc.name ?: "tool",
                                    arguments = tc.arguments ?: "",
                                )
                            },
                        )
                    }
                } else null
            // Intentionally never synthesize a standalone approvalResponse
            // here — see comment above. The chip on the tool card carries
            // the "Approved" indicator without hiding the tool body.
            val uiApprovalResponse: UiApprovalResponse? = null
            UiMessage(
                id = ev.serverId,
                role = role,
                content = ev.content,
                timestamp = ev.date.toString(),
                isPending = false,
                isReasoning = ev.messageType == TimelineMessageType.REASONING,
                toolCalls = uiToolCalls,
                approvalRequest = uiApproval,
                approvalResponse = uiApprovalResponse,
                attachments = ev.attachments.map {
                    UiImageAttachment(base64 = it.base64, mediaType = it.mediaType)
                },
            )
        }
    }
}
