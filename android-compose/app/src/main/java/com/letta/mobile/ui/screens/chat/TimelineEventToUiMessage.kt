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
import com.letta.mobile.data.timeline.stripEnvelopeReminders
import com.letta.mobile.util.Telemetry

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
        is TimelineEvent.Local -> {
            // letta-mobile-5s1n: Locals can now represent in-flight assistant
            // streaming (Client Mode harness path) in addition to the legacy
            // optimistic USER bubble. Standalone TOOL_RETURN Locals are
            // suppressed to mirror the Confirmed branch contract — tool
            // returns are folded into the invoking TOOL_CALL Local instead.
            if (ev.messageType == TimelineMessageType.TOOL_RETURN) return null
            if (ev.messageType == TimelineMessageType.OTHER) return null
            if (ev.messageType == TimelineMessageType.SYSTEM) return null
            val role = when (ev.messageType) {
                TimelineMessageType.USER -> "user"
                TimelineMessageType.ASSISTANT -> "assistant"
                TimelineMessageType.REASONING -> "assistant"
                TimelineMessageType.TOOL_CALL -> "assistant"
                TimelineMessageType.SYSTEM -> return null
                // Locals never originate as ERROR (server-only frame), but
                // make the `when` exhaustive so adding the type elsewhere
                // doesn't compile-warn here. letta-mobile-5s1n.
                TimelineMessageType.ERROR -> "system"
                TimelineMessageType.TOOL_RETURN, TimelineMessageType.OTHER ->
                    when (ev.role) {
                        Role.USER -> "user"
                        Role.ASSISTANT -> "assistant"
                        Role.SYSTEM -> "system"
                    }
            }
            // Mirror the Confirmed branch: tool-call Locals carry structured
            // tool calls + result/return body + approval state. Pending state
            // means SENDING; in-flight assistant streaming Locals use SENT so
            // the UI doesn't spin (the gateway is the delivery authority).
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

            // For REASONING locals, prefer reasoningContent if present (allows
            // the streaming path to set content="" and pipe partial reasoning
            // through reasoningContent for cleaner separation), falling back
            // to the standard content field for backward compatibility.
            val displayContent = when (ev.messageType) {
                TimelineMessageType.REASONING ->
                    ev.reasoningContent?.takeIf { it.isNotEmpty() } ?: ev.content
                else -> ev.content
            }.stripEnvelopeReminders()
            UiMessage(
                id = ev.otid,
                role = role,
                content = displayContent,
                timestamp = ev.sentAt.toString(),
                isPending = ev.deliveryState == DeliveryState.SENDING,
                isReasoning = ev.messageType == TimelineMessageType.REASONING,
                isError = ev.messageType == TimelineMessageType.ERROR,
                toolCalls = uiToolCalls,
                approvalRequest = uiApproval,
                approvalResponse = null,
                attachments = ev.attachments.map {
                    UiImageAttachment(base64 = it.base64, mediaType = it.mediaType)
                },
            )
        }
        is TimelineEvent.Confirmed -> {
            if (ev.messageType == TimelineMessageType.SYSTEM) return null
            
            val role = when (ev.messageType) {
                TimelineMessageType.USER -> "user"
                TimelineMessageType.ASSISTANT -> "assistant"
                TimelineMessageType.REASONING -> "assistant"
                TimelineMessageType.SYSTEM -> return null
                // letta-mobile-5s1n: ERROR frames render as a system bubble
                // with the destructive accent applied via UiMessage.isError.
                TimelineMessageType.ERROR -> "system"
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
            // letta-mobile-spqb probe: pin down whether the approval card is
            // being projected. If approvalRequestId is null OR
            // approvalDecided is true here, the card can never render —
            // the bug is upstream (toTimelineEvent / merge / reconcile).
            // If both are correct (id non-null, decided=false) and the user
            // still sees no card, the bug is downstream in Compose.
            if (ev.messageType == TimelineMessageType.TOOL_CALL ||
                ev.approvalRequestId != null ||
                ev.toolCalls.isNotEmpty()
            ) {
                Telemetry.event(
                    "TimelineSync", "uiProjection.approval",
                    "serverId" to ev.serverId,
                    "messageType" to ev.messageType.name,
                    "approvalRequestId" to (ev.approvalRequestId ?: "<null>"),
                    "approvalDecided" to ev.approvalDecided,
                    "toolCalls" to ev.toolCalls.size,
                    "uiApprovalEmitted" to (uiApproval != null),
                    "uiToolCallsEmitted" to (uiToolCalls?.size ?: 0),
                )
            }
            UiMessage(
                id = ev.serverId,
                role = role,
                content = ev.content.stripEnvelopeReminders(),
                timestamp = ev.date.toString(),
                runId = ev.runId,
                stepId = ev.stepId,
                isPending = false,
                isReasoning = ev.messageType == TimelineMessageType.REASONING,
                isError = ev.messageType == TimelineMessageType.ERROR,
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
