package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.messaging.AgentMessageProvenanceProjection
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolResultTruncation
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.stripEnvelopeReminders
import com.letta.mobile.data.timeline.timelineInstantDurationMillis

/**
 * lettabot-y4j (defensive client-side scrub):
 *
 * Lettabot wraps every inbound user message in a `<system-reminder>...
 * </system-reminder>` envelope before forwarding to the agent. Letta
 * persists the wrapped form in conversation history. The Android app
 * fetches history directly from the Letta server (not through lettabot's
 * REST proxy), so the server-side scrub in `conversations-proxy.ts` does
 * NOT run for our reads — leaked envelopes show up as user bubbles
 * containing the full metadata block.
 *
 * Mitigate at the rendering chokepoint: any USER-role timeline event
 * gets its content scrubbed of complete `<system-reminder>...
 * </system-reminder>` blocks. Defensive (cheap regex on already-small
 * content), idempotent, and survives any upstream path that sneaks an
 * envelope into the timeline.
 *
 * Returns null when the message becomes empty after stripping (envelope-
 * only payload) so the caller can drop the bubble entirely.
 */
private val SYSTEM_REMINDER_BLOCK = Regex(
    "<system-reminder>[\\s\\S]*?</system-reminder>",
    RegexOption.IGNORE_CASE,
)
// Orphan tags survive after block stripping when an envelope was
// truncated, malformed, or back-to-back with another envelope (e.g.
// `</system-reminder><system-reminder>X</system-reminder>` — the
// non-greedy block match consumes the inner pair and leaves the leading
// orphan close behind). Strip any standalone open/close markers as a
// final cleanup pass.
private val SYSTEM_REMINDER_ORPHAN_TAG = Regex(
    "</?system-reminder>",
    RegexOption.IGNORE_CASE,
)

fun scrubUserEnvelope(content: String): String {
    if (content.isEmpty()) return content
    if (!content.contains("<system-reminder", ignoreCase = true) &&
        !content.contains("</system-reminder", ignoreCase = true)) return content
    // 1) Strip well-formed <system-reminder>...</system-reminder> blocks.
    //    Run the regex to a fixed point so multiple back-to-back blocks
    //    inside a single content payload are all consumed even if the
    //    non-greedy match leaves the outer pair behind on the first pass.
    var out = content
    while (true) {
        val next = SYSTEM_REMINDER_BLOCK.replace(out, "")
        if (next == out) break
        out = next
    }
    // 2) Strip any orphan open/close tags left over from malformed or
    //    truncated envelopes. Their textual content (between an orphan
    //    open and the next orphan close, or trailing past an orphan close)
    //    is preserved — we'd rather show too much than swallow real user
    //    text.
    out = SYSTEM_REMINDER_ORPHAN_TAG.replace(out, "")
    return out.trim()
}

/**
 * Pure mapping from a [TimelineEvent] to the [UiMessage] the chat screen
 * renders. Kept as a top-level function so it can be unit-tested without
 * instantiating a platform view model.
 *
 * Contract:
 * - Standalone TOOL_RETURN events → null (should be merged into TOOL_CALL).
 * - TOOL_CALL events produce a UiMessage with populated `toolCalls` carrying
 *   name, arguments, result (from [TimelineEvent.Confirmed.toolReturnContent])
 *   and status.
 * - An ApprovalRequest that hasn't been decided produces `approvalRequest`
 *   with approve/reject buttons; once decided, it produces `approvalResponse`
 *   and clears `approvalRequest`.
 * - REASONING produces role="assistant" + isReasoning=true.
 * - SYSTEM events → null. Letta seeds every freshly-created conversation with
 *   a `system_message` carrying the agent's base instructions (visible via
 *   `GET /v1/conversations/:id/messages` immediately after `POST
 *   /v1/conversations`). Rendering those as bubbles produces the
 *   "miscellaneous message history in a brand-new conversation" bug
 *. The chat surface is for user-facing conversation
 *   only; agent-state system messages are not part of the conversational UX.
 *   Other surfaces that legitimately want to see system messages (Debug tab,
 *   tooling) should observe the timeline directly without going through this
 *   projection.
 */
fun timelineEventToUiMessage(
    ev: TimelineEvent,
    ownAgentId: String? = null,
    /** This event's compose receipts, from [CanvasArtifactReceipts.attach]. */
    artifacts: List<CanvasArtifactReceipt> = emptyList(),
): UiMessage? {
    val context = TimelineMessageProjectionContext(ownAgentId, artifacts)
    return when (ev) {
        is TimelineEvent.Local -> ev.toLocalUiMessage(context)
        // System rows must bypass even structured-notification recognition.
        is TimelineEvent.Confirmed ->
            if (ev.messageType == TimelineMessageType.SYSTEM) null else ev.toConfirmedUiMessage(context)
    }
}

private class TimelineMessageProjectionContext(
    val ownAgentId: String?,
    val artifacts: List<CanvasArtifactReceipt>,
)

private class ProjectedToolReturn(val callId: String?, val content: String?)

private class ToolCallPosition(val toolCall: ToolCall, val index: Int)

private fun TimelineMessageType.chatRole(): String? {
    return when (this) {
        TimelineMessageType.USER -> "user"
        TimelineMessageType.ASSISTANT, TimelineMessageType.REASONING, TimelineMessageType.TOOL_CALL -> "assistant"
        TimelineMessageType.ERROR -> "system"
        TimelineMessageType.SYSTEM, TimelineMessageType.COMPACTION,
        TimelineMessageType.TOOL_RETURN, TimelineMessageType.OTHER -> null
    }
}

private fun TimelineEvent.Local.toLocalUiMessage(
    context: TimelineMessageProjectionContext,
): UiMessage? {
    // Standalone returns are merged into their invoking tool call, never their own row.
    val role = messageType.chatRole() ?: return null
    val uiToolCalls = localUiToolCalls(context)
    if (isEmptyToolCall(uiToolCalls)) return null
    return UiMessage(
        id = otid,
        role = role,
        content = localDisplayContent(uiToolCalls),
        timestamp = sentAt.toString(),
        clientMessageId = otid,
        isPending = deliveryState == DeliveryState.SENDING,
        isReasoning = messageType == TimelineMessageType.REASONING,
        // Failed delivery marks the user's send failed, not a server error bubble.
        isError = messageType == TimelineMessageType.ERROR,
        isSendFailed = deliveryState == DeliveryState.FAILED,
        toolCalls = uiToolCalls,
        approvalRequest = pendingApprovalRequest(approvalDecided, approvalRequestId, toolCalls),
        approvalResponse = null,
        attachments = messageAttachments(attachments, uiToolCalls),
        // Locals have no owning agent yet; the decoded envelope provides the recipient fallback.
        agentMessageProvenance = if (role == "user") {
            AgentMessageProvenanceProjection.projectInbound(clientMessageId = otid, ownAgentId = context.ownAgentId)
        } else {
            null
        },
        artifacts = context.artifacts,
    )
}

private fun TimelineEvent.Local.isEmptyToolCall(uiToolCalls: List<UiToolCall>?): Boolean {
    if (messageType != TimelineMessageType.TOOL_CALL) return false
    return uiToolCalls == null && content.isBlank()
}

private fun TimelineEvent.Local.localDisplayContent(uiToolCalls: List<UiToolCall>?): String {
    val displayContent = when (messageType) {
        // Streaming reasoning can arrive separately from the ordinary content field.
        TimelineMessageType.REASONING -> reasoningContent?.takeIf { it.isNotEmpty() } ?: content
        TimelineMessageType.TOOL_CALL -> if (uiToolCalls != null) "" else content
        else -> content
    }
    return displayContent.stripEnvelopeReminders()
}

private fun TimelineEvent.Local.localUiToolCalls(context: TimelineMessageProjectionContext): List<UiToolCall>? {
    if (toolCalls.isEmpty()) return null
    val chip = implicitApprovalChip(
        decided = approvalDecided,
        approvalRequestId = approvalRequestId,
        anyToolReturnError = toolReturnIsError || toolReturnIsErrorByCallId.values.any { it },
    )
    return toolCalls.mapIndexed { index, tc -> localUiToolCall(ToolCallPosition(tc, index), chip, context) }
}

private fun TimelineEvent.Local.localUiToolCall(
    position: ToolCallPosition,
    chip: UiToolApprovalDecision?,
    context: TimelineMessageProjectionContext,
): UiToolCall {
    val tc = position.toolCall
    val callId = tc.effectiveId.takeIf { it.isNotBlank() }
    val result = callId?.let { toolReturnContentByCallId[it] }
        ?: if (position.index == 0) toolReturnContent else null
    val isError = callId?.let { toolReturnIsErrorByCallId[it] } ?: toolReturnIsError
    return tc.toUiToolCall(
        ToolCallRenderData(
            result = result,
            isError = isError,
            generatedImageAttachments = tc.generatedImageAttachments(attachments),
            executionTimeMs = localExecutionTimeMs(ProjectedToolReturn(callId, result)),
            approvalDecision = chip,
            sourceAgentId = context.ownAgentId,
        ),
    )
}

private fun TimelineEvent.Local.localExecutionTimeMs(result: ProjectedToolReturn): Long? {
    val callId = result.callId
    if (result.content == null || callId == null) return null
    return toolStartedAtByCallId[callId]?.let { startedAt ->
        val completedAt = toolCompletedAtByCallId[callId] ?: sentAt
        timelineInstantDurationMillis(startedAt, completedAt).coerceAtLeast(0L)
    }
}

private fun TimelineEvent.Confirmed.toConfirmedUiMessage(context: TimelineMessageProjectionContext): UiMessage? {
    confirmedStructuredRow(this)?.let { return it }
    val role = messageType.chatRole() ?: return null
    val uiToolCalls = confirmedUiToolCalls()
    val confirmedContent = confirmedDisplayContent(uiToolCalls) ?: return null
    return UiMessage(
        // Server reasoning and assistant events may share a server id; preserve both row identities.
        id = if (messageType == TimelineMessageType.REASONING) "$serverId:${messageType.name}" else serverId,
        role = role,
        content = confirmedContent,
        timestamp = date.toString(),
        runId = runId,
        agentId = agentId,
        stepId = stepId,
        clientMessageId = otid.takeIf { it.isNotBlank() },
        isPending = false,
        isReasoning = messageType == TimelineMessageType.REASONING,
        isError = messageType == TimelineMessageType.ERROR,
        toolCalls = uiToolCalls,
        approvalRequest = pendingApprovalRequest(approvalDecided, approvalRequestId, toolCalls),
        // A standalone approval response would hide the rich tool body. Its chip carries the decision.
        approvalResponse = null,
        attachments = messageAttachments(attachments, uiToolCalls),
        agentMessageProvenance = if (role == "user") {
            AgentMessageProvenanceProjection.projectInbound(clientMessageId = otid, ownAgentId = agentId)
        } else {
            null
        },
        artifacts = context.artifacts,
    )
}

private fun TimelineEvent.Confirmed.confirmedDisplayContent(uiToolCalls: List<UiToolCall>?): String? {
    return when {
        messageType == TimelineMessageType.TOOL_CALL && uiToolCalls != null -> ""
        messageType == TimelineMessageType.USER -> {
            // History reads bypass the upstream envelope scrub; envelope-only bubbles must disappear.
            val stripped = scrubUserEnvelope(content)
            stripped.takeUnless { it.isEmpty() && content.isNotEmpty() }
        }
        else -> content
    }
}

private fun TimelineEvent.Confirmed.confirmedUiToolCalls(): List<UiToolCall>? {
    if (toolCalls.isEmpty()) return null
    val chip = approvalChip()
    return toolCalls.mapIndexed { index, tc -> confirmedUiToolCall(ToolCallPosition(tc, index), chip) }
}

private fun TimelineEvent.Confirmed.confirmedUiToolCall(
    position: ToolCallPosition,
    chip: UiToolApprovalDecision?,
): UiToolCall {
    val tc = position.toolCall
    val callId = tc.effectiveId.takeIf { it.isNotBlank() }
    val result = callId?.let { toolReturnContentByCallId[it] }
        ?: if (position.index == 0) toolReturnContent else null
    val isError = callId?.let { toolReturnIsErrorByCallId[it] } ?: toolReturnIsError
    val truncation = callId?.let { toolReturnTruncationByCallId[it] }
    return tc.toUiToolCall(
        ToolCallRenderData(
            result = result,
            isError = isError,
            generatedImageAttachments = tc.generatedImageAttachments(attachments),
            approvalDecision = chip,
            // Preserve the pointer marker so expanding the card can fetch the complete result body.
            resultTruncation = truncation?.let { UiToolResultTruncation(messageId = it.messageId, byteLen = it.byteLen) },
            sourceAgentId = agentId,
        ),
    )
}

private fun messageAttachments(
    attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>,
    uiToolCalls: List<UiToolCall>?,
): List<UiImageAttachment> {
    return if (uiToolCalls.orEmpty().any { it.name == "generate_image" }) {
        emptyList()
    } else {
        attachments.map { UiImageAttachment(base64 = it.base64, mediaType = it.mediaType, storedByteSize = it.storedByteSize) }
    }
}

/** The approval buttons for [toolCalls] while request [requestId] is undecided; null once decided or with no request. */
private fun pendingApprovalRequest(
    decided: Boolean,
    requestId: String?,
    toolCalls: List<com.letta.mobile.data.model.ToolCall>,
): UiApprovalRequest? {
    if (decided) return null
    val reqId = requestId ?: return null
    return UiApprovalRequest(
        requestId = reqId,
        toolCalls = toolCalls.map { tc ->
            UiApprovalToolCall(
                toolCallId = tc.effectiveId,
                name = tc.name ?: "tool",
                arguments = tc.arguments ?: "",
            )
        },
    )
}

/**
 * The chip for a request resolved without an explicit decision - the
 * approve=null auto-approval echo, or a tool return that completed it. It fails closed: a rejected
 * call comes back as an error tool return, so a request whose missing explicit decision is paired
 * with an error return gets no chip at all rather than reading as Approved. Only a clean resolution
 * keeps the Approved label.
 */
internal fun implicitApprovalChip(
    decided: Boolean,
    approvalRequestId: String?,
    anyToolReturnError: Boolean,
): UiToolApprovalDecision? = when {
    !decided || approvalRequestId == null -> null
    anyToolReturnError -> null
    else -> UiToolApprovalDecision.Approved
}
