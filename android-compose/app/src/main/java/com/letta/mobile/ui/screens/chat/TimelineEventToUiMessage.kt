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
import com.letta.mobile.util.Telemetry

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

internal fun scrubUserEnvelope(content: String): String {
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
 * - SYSTEM events → null. Letta seeds every freshly-created conversation with
 *   a `system_message` carrying the agent's base instructions (visible via
 *   `GET /v1/conversations/:id/messages` immediately after `POST
 *   /v1/conversations`). Rendering those as bubbles produces the
 *   "miscellaneous message history in a brand-new conversation" bug
 *   `letta-mobile-e75s`. The chat surface is for user-facing conversation
 *   only; agent-state system messages are not part of the conversational UX.
 *   Other surfaces that legitimately want to see system messages (Debug tab,
 *   tooling) should observe the timeline directly without going through this
 *   projection.
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
            // letta-mobile-e75s: drop SYSTEM Locals. We don't currently
            // synthesize SYSTEM Locals anywhere on the client, but keep the
            // suppression symmetric with the Confirmed branch in case future
            // code does (defense in depth — failure mode is a leaked
            // base-prompt bubble in chat).
            if (ev.messageType == TimelineMessageType.SYSTEM) return null
            val role = when (ev.messageType) {
                TimelineMessageType.USER -> "user"
                TimelineMessageType.ASSISTANT -> "assistant"
                TimelineMessageType.REASONING -> "assistant"
                TimelineMessageType.TOOL_CALL -> "assistant"
                TimelineMessageType.SYSTEM -> "system"
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
            }
            // lettabot-y4j: defensive strip of leaked envelope blocks on
            // USER bubbles. If nothing remains after stripping, drop the
            // bubble entirely (it was envelope-only).
            val finalContent = if (role == "user") {
                val stripped = scrubUserEnvelope(displayContent)
                if (stripped.isEmpty() && displayContent.isNotEmpty()) return null
                stripped
            } else displayContent
            UiMessage(
                id = ev.otid,
                role = role,
                content = finalContent,
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
            // letta-mobile-e75s: drop SYSTEM Confirmed events from the chat
            // projection. Letta seeds every freshly-created conversation with
            // a system_message containing the agent's base instructions; if
            // we render that as a bubble the user sees their brand-new chat
            // populated with "miscellaneous message history" before they've
            // even sent anything (Emmanuel report 2026-04-28).
            if (ev.messageType == TimelineMessageType.SYSTEM) return null
            val role = when (ev.messageType) {
                TimelineMessageType.USER -> "user"
                TimelineMessageType.ASSISTANT -> "assistant"
                TimelineMessageType.REASONING -> "assistant"
                // SYSTEM is filtered out above (e75s). Keep the branch
                // exhaustive to preserve `when` warnings if the enum changes.
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
            // lettabot-y4j: defensive strip of leaked envelope blocks on
            // USER bubbles. Confirmed events come from `GET /v1/conversations/
            // :id/messages` which goes directly to the Letta server (the
            // lettabot REST scrub doesn't run here), so we MUST strip on
            // render. Drop the bubble entirely if the content was envelope-only.
            val confirmedContent = if (role == "user") {
                val stripped = scrubUserEnvelope(ev.content)
                if (stripped.isEmpty() && ev.content.isNotEmpty()) return null
                stripped
            } else ev.content
            UiMessage(
                id = ev.serverId,
                role = role,
                content = confirmedContent,
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
