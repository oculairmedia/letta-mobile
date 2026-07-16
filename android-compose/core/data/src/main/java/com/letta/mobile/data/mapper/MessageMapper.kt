package com.letta.mobile.data.mapper

import com.letta.mobile.data.model.AppMessage
import com.letta.mobile.data.model.ApprovalDecisionPayload
import com.letta.mobile.data.model.ApprovalRequestMessage
import com.letta.mobile.data.model.ApprovalRequestPayload
import com.letta.mobile.data.model.ApprovalResponseMessage
import com.letta.mobile.data.model.ApprovalResponsePayload
import com.letta.mobile.data.model.ApprovalToolCallPayload
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.GeneratedUiPayload
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageType
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.model.UiApprovalDecision
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiSubagentNotification
import com.letta.mobile.data.model.UiToolCall
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ToolCallContext(
    val name: String,
    val arguments: String,
)

class MessageMappingState internal constructor(
    internal val toolCallsById: MutableMap<String, ToolCallContext> = mutableMapOf(),
)

private val generatedUiToolNames = setOf(
    "render_summary_card",
    "render_metric_card",
    "render_suggestion_chips",
)

fun List<LettaMessage>.toAppMessages(): List<AppMessage> {
    val state = MessageMappingState()
    return mapNotNull { it.toAppMessage(state) }
}

fun LettaMessage.toAppMessage(): AppMessage? {
    return toAppMessage(MessageMappingState())
}

fun LettaMessage.toAppMessage(state: MessageMappingState): AppMessage? {
    return when (this) {
        is UserMessage -> AppMessage(
            id = id,
            date = date?.toInstant() ?: Instant.now(),
            messageType = MessageType.USER,
            content = content,
            runId = runId,
            stepId = stepId,
            // letta-mobile-mge5.24: carry any inline image parts through so
            // re-hydrating history from /messages shows the original image
            // bubble instead of a text-only placeholder.
            attachments = attachments,
        )
        is AssistantMessage -> {
            val generatedUi = extractGeneratedUi(contentRaw)
            AppMessage(
                id = id,
                date = date?.toInstant() ?: Instant.now(),
                messageType = MessageType.ASSISTANT,
                content = generatedUi?.fallbackText.orEmpty().ifBlank {
                    if (generatedUi != null) "" else content
                },
                runId = runId,
                stepId = stepId,
                generatedUi = generatedUi,
                attachments = attachments,
            )
        }
        is ReasoningMessage -> AppMessage(
            id = id,
            date = date?.toInstant() ?: Instant.now(),
            messageType = MessageType.REASONING,
            content = reasoning,
            runId = runId,
            stepId = stepId,
        )
        is ToolCallMessage -> {
            val toolCall = effectiveToolCalls.firstOrNull()
            val toolCallId = toolCall?.effectiveId
            val toolName = toolCall?.name
            val arguments = toolCall?.arguments.orEmpty()
            if (!toolCallId.isNullOrBlank() && !toolName.isNullOrBlank()) {
                state.toolCallsById[toolCallId] = ToolCallContext(name = toolName, arguments = arguments)
            }
            AppMessage(
                id = id,
                date = date?.toInstant() ?: Instant.now(),
                messageType = MessageType.TOOL_CALL,
                content = arguments,
                runId = runId,
                stepId = stepId,
                toolName = toolName,
                toolCallId = toolCallId,
            )
        }
        is ApprovalRequestMessage -> {
            val toolCalls = effectiveToolCalls.mapNotNull { toolCall ->
                val toolCallId = toolCall.effectiveId.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val toolName = toolCall.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val arguments = toolCall.arguments.orEmpty()
                state.toolCallsById[toolCallId] = ToolCallContext(name = toolName, arguments = arguments)
                ApprovalToolCallPayload(
                    toolCallId = toolCallId,
                    name = toolName,
                    arguments = arguments,
                )
            }
            AppMessage(
                id = id,
                date = date?.toInstant() ?: Instant.now(),
                messageType = MessageType.APPROVAL_REQUEST,
                content = "",
                runId = runId,
                stepId = stepId,
                approvalRequest = ApprovalRequestPayload(
                    requestId = id,
                    toolCalls = toolCalls,
                ),
            )
        }
        is ToolReturnMessage -> {
            val toolCallId = toolReturn.toolCallId
            val context = state.toolCallsById[toolCallId]
            AppMessage(
                id = id,
                date = date?.toInstant() ?: Instant.now(),
                messageType = MessageType.TOOL_RETURN,
                content = toolReturn.funcResponse ?: "",
                runId = runId,
                stepId = stepId,
                toolName = context?.name ?: name,
                toolCallId = toolCallId,
                toolReturnStatus = toolReturn.status,
                attachments = attachments,
            )
        }
        is ApprovalResponseMessage -> AppMessage(
            id = id,
            date = date?.toInstant() ?: Instant.now(),
            messageType = MessageType.APPROVAL_RESPONSE,
            content = "",
            runId = runId,
            stepId = stepId,
            approvalResponse = ApprovalResponsePayload(
                requestId = approvalRequestId,
                approved = approve,
                reason = reason,
                approvals = approvals.orEmpty().mapNotNull { approval ->
                    val toolCallId = approval.toolCallId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    ApprovalDecisionPayload(
                        toolCallId = toolCallId,
                        approved = approval.approve,
                        status = approval.status,
                        reason = approval.reason,
                    )
                },
            ),
        )
        else -> null
    }
}

/**
 * Folded approval decision resolved from an `APPROVAL_RESPONSE` payload that
 * targets a specific tool call. Emitted by [buildFoldedApprovalIndex] and
 * consumed by [toUiMessages] when attaching a chip to the tool-call card.
 *
 * `carriedReason` is true when the underlying response also supplied a reason
 * string — in that case we still keep the standalone approval card around so
 * the note is visible (letta-mobile-23h5).
 */
private data class FoldedToolApproval(
    val decision: UiToolApprovalDecision,
    val carriedReason: Boolean,
    val sourceMessageId: String,
)

/**
 * Pre-scan the flat message list for `APPROVAL_RESPONSE` events and index any
 * per-call decisions by `toolCallId`. Only top-level-explicit or per-call
 * decisions are returned — bare `approve=null` bookkeeping echoes are left
 * alone (those are dropped entirely by the existing APPROVAL_RESPONSE branch).
 */
private fun List<AppMessage>.buildFoldedApprovalIndex(): Map<String, FoldedToolApproval> {
    val index = mutableMapOf<String, FoldedToolApproval>()
    for (msg in this) {
        if (msg.messageType != MessageType.APPROVAL_RESPONSE) continue
        val response = msg.approvalResponse ?: continue
        val topLevelReason = response.reason?.takeIf { it.isNotBlank() }
        // When the response only carries a top-level approve=true/false (no
        // per-call breakdown), fold it onto whatever tool calls the matching
        // request was about. We don't have that linkage here without walking
        // the APPROVAL_REQUEST, so leave those as standalone cards for now —
        // the common Letta Code case populates per-call `approvals[]`.
        for (decision in response.approvals) {
            val toolCallId = decision.toolCallId.takeIf { it.isNotBlank() } ?: continue
            val decided = decision.approved ?: continue
            val reasonBlank = decision.reason.isNullOrBlank() && topLevelReason == null
            index[toolCallId] = FoldedToolApproval(
                decision = if (decided) UiToolApprovalDecision.Approved else UiToolApprovalDecision.Rejected,
                carriedReason = !reasonBlank,
                sourceMessageId = msg.id,
            )
        }
    }
    return index
}

fun List<AppMessage>.toUiMessages(): List<UiMessage> {
    val returnsByCallId = mutableMapOf<String, AppMessage>()
    for (msg in this) {
        val callId = msg.toolCallId
        if (msg.messageType == MessageType.TOOL_RETURN && !callId.isNullOrBlank()) {
            returnsByCallId[callId] = msg
        }
    }
    val subagentToolCallByTaskId = mutableMapOf<String, String>()
    for (msg in this) {
        if (msg.messageType != MessageType.TOOL_CALL || msg.toolName != "Agent") continue
        val callId = msg.toolCallId?.takeIf { it.isNotBlank() } ?: continue
        val dispatch = extractSubagentDispatch(
            toolCallId = callId,
            arguments = msg.content,
            returnContent = returnsByCallId[callId]?.content,
        ) ?: continue
        dispatch.taskId?.takeIf { it.isNotBlank() }?.let { taskId ->
            // Deterministic on taskId collision: keep the FIRST dispatch in
            // conversation order as the owner of that taskId, rather than
            // last-write-wins (which could mis-correlate a later notification
            // to the wrong tool call). Server taskIds are normally unique per
            // conversation; this only guards the collision edge case.
            subagentToolCallByTaskId.putIfAbsent(taskId, callId)
        }
    }

    // The set of tool-call ids that will actually produce a visible bubble in
    // this render. A folded approval decision can only silently replace its
    // standalone card if the target tool call is going to be present — if it
    // never arrived (e.g. a rogue approval_response with no matching
    // tool_call_message) the decision would be invisible, which would regress
    // the legacy behaviour.
    val renderedToolCallIds = buildSet {
        for (msg in this@toUiMessages) {
            if (msg.messageType == MessageType.TOOL_CALL || msg.messageType == MessageType.TOOL_RETURN) {
                msg.toolCallId?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    val foldedApprovals = buildFoldedApprovalIndex()
        .filterKeys { it in renderedToolCallIds }
    // APPROVAL_RESPONSE source messages whose per-call decisions were ALL
    // absorbed (and which carried no user-written reason) can be suppressed
    // entirely. Anything else — a reason, a rejection, or decisions that
    // didn't match any tool call — falls through to the standalone card.
    val fullyAbsorbedResponseIds = mutableSetOf<String>()
    run {
        val responsesToDecisions = mutableMapOf<String, MutableList<FoldedToolApproval>>()
        for ((_, folded) in foldedApprovals) {
            responsesToDecisions.getOrPut(folded.sourceMessageId) { mutableListOf() }.add(folded)
        }
        for (msg in this) {
            if (msg.messageType != MessageType.APPROVAL_RESPONSE) continue
            val response = msg.approvalResponse ?: continue
            val folded = responsesToDecisions[msg.id].orEmpty()
            if (folded.isEmpty()) continue
            // Every per-call decision in the response must be (a) explicit
            // (approve != null), (b) target a tool call that will actually
            // render, and (c) be the Approved kind with no reason. Anything
            // else falls through to the standalone card so the user can still
            // see the outcome.
            val explicitPerCall = response.approvals.filter {
                !it.toolCallId.isNullOrBlank() && it.approved != null
            }
            if (explicitPerCall.isEmpty()) continue
            if (explicitPerCall.any { it.toolCallId !in renderedToolCallIds }) continue
            if (folded.size != explicitPerCall.size) continue
            if (folded.any { it.carriedReason }) continue
            if (!response.reason.isNullOrBlank()) continue
            // Only absorb Approved decisions silently. A Rejected outcome is
            // consequential enough on its own that we keep the standalone
            // card even without a reason — users should always see a
            // rejection spelled out.
            if (folded.any { it.decision == UiToolApprovalDecision.Rejected }) continue
            fullyAbsorbedResponseIds.add(msg.id)
        }
    }

    val consumedReturnIds = mutableSetOf<String>()

    val result = mutableListOf<UiMessage>()
    for (msg in this) {
        when (msg.messageType) {
            MessageType.TOOL_CALL -> {
                val matchedReturn = msg.toolCallId?.let(returnsByCallId::get)
                if (matchedReturn != null) {
                    consumedReturnIds.add(matchedReturn.id)
                }
                val name = msg.toolName
                val arguments = msg.content
                val returnContent = matchedReturn?.content
                val returnStatus = matchedReturn?.toolReturnStatus
                val executionTimeMs = matchedReturn?.let { toolReturn ->
                    Duration.between(msg.date, toolReturn.date).toMillis().takeIf { it >= 0L }
                }

                if (name in generatedUiToolNames && returnContent != null) {
                    val generatedUi = extractGeneratedUiFromString(returnContent)
                    if (generatedUi != null) {
                        result.add(
                            UiMessage(
                                id = msg.id,
                                role = "assistant",
                                content = generatedUi.fallbackText.orEmpty(),
                                timestamp = msg.date.toString(),
                                runId = msg.runId,
                                stepId = msg.stepId,
                                generatedUi = UiGeneratedComponent(
                                    name = generatedUi.component,
                                    propsJson = generatedUi.propsJson,
                                    fallbackText = generatedUi.fallbackText,
                                ),
                            )
                        )
                        continue
                    }
                }

                // send_message is Letta's reply tool — promote to assistant bubble
                if (name == "send_message" && returnContent != null) {
                    val visibleText = extractSendMessageText(arguments, returnContent)
                    if (visibleText.isNotBlank()) {
                        result.add(UiMessage(
                            id = msg.id,
                            role = "assistant",
                            content = visibleText,
                            timestamp = msg.date.toString(),
                            runId = msg.runId,
                            stepId = msg.stepId,
                        ))
                        continue
                    }
                }

                val subagentDispatch = if (name == "Agent") {
                    extractSubagentDispatch(
                        toolCallId = msg.toolCallId,
                        arguments = arguments,
                        returnContent = returnContent,
                    )
                } else {
                    null
                }
                val toolCall = UiToolCall(
                    name = name ?: "tool",
                    arguments = arguments,
                    result = returnContent,
                    status = returnStatus,
                    generatedImageAttachments = if (name == "generate_image") {
                        matchedReturn?.attachments.orEmpty().map {
                            UiImageAttachment(base64 = it.base64, mediaType = it.mediaType)
                        }
                    } else {
                        emptyList()
                    },
                    executionTimeMs = executionTimeMs,
                    toolCallId = msg.toolCallId,
                    approvalDecision = msg.toolCallId?.let { foldedApprovals[it]?.decision },
                    subagentDispatch = subagentDispatch,
                )
                result.add(UiMessage(
                    id = msg.id,
                    role = "tool",
                    content = "",
                    timestamp = msg.date.toString(),
                    runId = msg.runId,
                    stepId = msg.stepId,
                    toolCalls = listOf(toolCall),
                    attachments = if (name == "generate_image") {
                        emptyList()
                    } else {
                        matchedReturn?.attachments.orEmpty().map {
                            UiImageAttachment(base64 = it.base64, mediaType = it.mediaType)
                        }
                    },
                ))
            }

            MessageType.TOOL_RETURN -> {
                if (msg.id in consumedReturnIds) continue
                val name = msg.toolName ?: "tool"

                if (name in generatedUiToolNames && msg.content.isNotBlank()) {
                    val generatedUi = extractGeneratedUiFromString(msg.content)
                    if (generatedUi != null) {
                        result.add(
                            UiMessage(
                                id = msg.id,
                                role = "assistant",
                                content = generatedUi.fallbackText.orEmpty(),
                                timestamp = msg.date.toString(),
                                runId = msg.runId,
                                stepId = msg.stepId,
                                generatedUi = UiGeneratedComponent(
                                    name = generatedUi.component,
                                    propsJson = generatedUi.propsJson,
                                    fallbackText = generatedUi.fallbackText,
                                ),
                            )
                        )
                        continue
                    }
                }

                if (name == "send_message" && msg.content.isNotBlank()) {
                    result.add(UiMessage(
                        id = msg.id,
                        role = "assistant",
                        content = msg.content,
                        timestamp = msg.date.toString(),
                        runId = msg.runId,
                        stepId = msg.stepId,
                    ))
                    continue
                }

                val subagentDispatch = if (name == "Agent") {
                    extractSubagentDispatch(
                        toolCallId = msg.toolCallId,
                        arguments = "",
                        returnContent = msg.content,
                    )
                } else {
                    null
                }
                val toolCall = UiToolCall(
                    name = name,
                    arguments = "",
                    result = msg.content.ifBlank { null },
                    status = msg.toolReturnStatus,
                    generatedImageAttachments = if (name == "generate_image") {
                        msg.attachments.map {
                            UiImageAttachment(base64 = it.base64, mediaType = it.mediaType)
                        }
                    } else {
                        emptyList()
                    },
                    toolCallId = msg.toolCallId,
                    approvalDecision = msg.toolCallId?.let { foldedApprovals[it]?.decision },
                    subagentDispatch = subagentDispatch,
                )
                result.add(UiMessage(
                    id = msg.id,
                    role = "tool",
                    content = "",
                    timestamp = msg.date.toString(),
                    runId = msg.runId,
                    stepId = msg.stepId,
                    toolCalls = listOf(toolCall),
                    attachments = if (name == "generate_image") {
                        emptyList()
                    } else {
                        msg.attachments.map {
                            UiImageAttachment(base64 = it.base64, mediaType = it.mediaType)
                        }
                    },
                ))
            }

            // letta-mobile-rnocg: USER-role messages can carry an injected
            // `<task-notification>` agent-return (background subagent finishing).
            // `toUiMessage` already reclassifies those to role="assistant" with a
            // populated subagentNotification; route them through the SAME
            // task-id -> Agent tool-call correlation as the ASSISTANT path so the
            // return card links back to its dispatch chip / todo sheet.
            MessageType.USER, MessageType.ASSISTANT ->
                result.add(msg.toUiMessage().correlateSubagentNotification(subagentToolCallByTaskId))
            MessageType.REASONING -> result.add(msg.toUiMessage())
            MessageType.APPROVAL_REQUEST -> result.add(msg.toUiMessage())
            MessageType.APPROVAL_RESPONSE -> {
                // Suppress auto-approval response cards: in bypassPermissions sessions
                // the Letta server emits approval_response_message entries with
                // approve=null (and per-call approvals[].approve=null). These are
                // NOT user-facing rejections — they are bookkeeping echoes of the
                // tool_return. Rendering them as "Rejected" cards (the legacy
                // behaviour) misled operators on every yolo session.
                //
                // Only render the card when SOMEONE actually made an explicit
                // decision (approve == true OR approve == false at either the
                // top-level or any per-call entry).
                val response = msg.approvalResponse
                val hasExplicitDecision = response != null && (
                    response.approved != null ||
                    response.approvals.any { it.approved != null }
                )
                // letta-mobile-23h5: if every per-call decision was silently
                // absorbed onto its tool-call card (approve=true, no reason),
                // skip the standalone "Approved" bubble altogether — the chip
                // on the tool card carries the same info without polluting
                // the timeline with redundant pills.
                if (msg.id in fullyAbsorbedResponseIds) {
                    continue
                }
                if (hasExplicitDecision) {
                    result.add(msg.toUiMessage())
                }
                // else: silently drop — the linked tool_return already carries
                // the actual outcome and is rendered by the TOOL_CALL/TOOL_RETURN
                // branches above.
            }
        }
    }
    return result
}

/**
 * letta-mobile-rnocg: correlate a subagent-return notification back to the
 * `Agent` tool call that dispatched it. The notification may carry its own
 * `tool_call_id`; if not, we resolve it from the `task_id` -> Agent tool-call
 * id index built upstream. With the tool-call id attached, the return card can
 * open the dispatch's todo sheet / subagent conversation (same correlation seam
 * as the dispatch chip, pbnxa #343). No-op when the message carries no
 * notification.
 */
private fun UiMessage.correlateSubagentNotification(
    subagentToolCallByTaskId: Map<String, String>,
): UiMessage {
    val notification = subagentNotification ?: return this
    val taskId = notification.taskId
    val correlatedToolCallId = when {
        !notification.toolCallId.isNullOrBlank() -> notification.toolCallId
        !taskId.isNullOrBlank() -> subagentToolCallByTaskId[taskId]
        else -> null
    }
    return if (correlatedToolCallId != notification.toolCallId) {
        copy(subagentNotification = notification.copy(toolCallId = correlatedToolCallId))
    } else {
        this
    }
}

private fun extractSendMessageText(arguments: String, returnContent: String): String {
    return try {
        val msgStart = arguments.indexOf("\"message\"")
        if (msgStart < 0) return returnContent
        val colonIdx = arguments.indexOf(':', msgStart)
        if (colonIdx < 0) return returnContent
        val valStart = arguments.indexOf('"', colonIdx + 1)
        if (valStart < 0) return returnContent
        var i = valStart + 1
        val sb = StringBuilder()
        while (i < arguments.length) {
            val c = arguments[i]
            if (c == '\\' && i + 1 < arguments.length) {
                val next = arguments[i + 1]
                when (next) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> { sb.append('\\'); sb.append(next) }
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        sb.toString().ifBlank { returnContent }
    } catch (_: Exception) {
        returnContent
    }
}

fun AppMessage.toUiMessage(): UiMessage {
    // letta-mobile-rnocg: a completed background subagent surfaces its return
    // by INJECTING a `<task-notification>` envelope into the conversation as a
    // USER-role message (the server's async-tool-return convention). Rendered
    // naively that paints a giant green right-aligned user bubble full of raw
    // XML + the subagent's markdown report — reading as if the operator sent a
    // wall of XML. Detect that envelope on EITHER a USER or ASSISTANT inbound
    // message and reclassify it as an agent-return so it routes to the
    // left-aligned, recede-by-default SubagentNotificationCard instead of the
    // user bubble. Note: ASSISTANT was already parsed below; here we also catch
    // the USER injection path (the actual on-device bug).
    val subagentNotification = when (messageType) {
        MessageType.ASSISTANT, MessageType.USER -> extractSubagentNotification(content)
        else -> null
    }
    val role = when {
        // A task-notification is an agent-return, never user input — flip the
        // role so alignment, bubble shape, avatar, and renderer all treat it as
        // an agent-side message.
        subagentNotification != null -> "assistant"
        else -> when (messageType) {
            MessageType.USER -> "user"
            MessageType.ASSISTANT -> "assistant"
            MessageType.REASONING -> "assistant"
            MessageType.TOOL_CALL -> "tool"
            MessageType.TOOL_RETURN -> "tool"
            MessageType.APPROVAL_REQUEST -> "approval"
            MessageType.APPROVAL_RESPONSE -> "approval"
        }
    }
    val toolCalls = when {
        messageType == MessageType.TOOL_CALL -> {
            val subagentDispatch = if (toolName == "Agent") {
                extractSubagentDispatch(
                    toolCallId = toolCallId,
                    arguments = content,
                    returnContent = null,
                )
            } else {
                null
            }
            listOf(
                UiToolCall(
                    name = toolName ?: "tool",
                    arguments = content,
                    result = null,
                    toolCallId = toolCallId,
                    subagentDispatch = subagentDispatch,
                )
            )
        }
        messageType == MessageType.TOOL_RETURN -> {
            val subagentDispatch = if (toolName == "Agent") {
                extractSubagentDispatch(
                    toolCallId = toolCallId,
                    arguments = "",
                    returnContent = content,
                )
            } else {
                null
            }
            listOf(
                UiToolCall(
                    name = toolName ?: "tool",
                    arguments = "",
                    result = content.ifBlank { null },
                    toolCallId = toolCallId,
                    subagentDispatch = subagentDispatch,
                )
            )
        }
        else -> null
    }
    val displayContent = when {
        messageType == MessageType.TOOL_CALL -> ""
        messageType == MessageType.TOOL_RETURN -> ""
        subagentNotification != null -> ""
        else -> content
    }

    return UiMessage(
        id = id,
        role = role,
        content = displayContent,
        timestamp = date.toString(),
        runId = runId,
        stepId = stepId,
        isPending = isPending,
        isReasoning = messageType == MessageType.REASONING,
        toolCalls = toolCalls,
        generatedUi = generatedUi?.let {
            UiGeneratedComponent(
                name = it.component,
                propsJson = it.propsJson,
                fallbackText = it.fallbackText,
            )
        },
        approvalRequest = approvalRequest?.let {
            UiApprovalRequest(
                requestId = it.requestId,
                toolCalls = it.toolCalls.map { toolCall ->
                    UiApprovalToolCall(
                        toolCallId = toolCall.toolCallId,
                        name = toolCall.name,
                        arguments = toolCall.arguments,
                    )
                },
            )
        },
        approvalResponse = approvalResponse?.let {
            UiApprovalResponse(
                requestId = it.requestId,
                approved = it.approved,
                reason = it.reason,
                approvals = it.approvals.map { approval ->
                    UiApprovalDecision(
                        toolCallId = approval.toolCallId,
                        approved = approval.approved,
                        status = approval.status,
                        reason = approval.reason,
                    )
                },
            )
        },
        subagentNotification = subagentNotification,
        // letta-mobile-mge5.24: surface inline image parts extracted during
        // AppMessage mapping. USER/ASSISTANT bubbles will render a
        // MessageAttachmentsGrid alongside the text content.
        attachments = attachments.map {
            UiImageAttachment(base64 = it.base64, mediaType = it.mediaType)
        },
    )
}

private fun extractSubagentDispatch(
    toolCallId: String?,
    arguments: String,
    returnContent: String?,
): UiSubagentDispatch? {
    val args = parseJsonObject(arguments) ?: return null
    val description = args.stringField("description")
        ?: args.stringField("prompt")?.lineSequence()?.firstOrNull()?.take(96)
        ?: "Subagent"
    val subagentType = args.stringField("subagent_type")
        ?: args.stringField("subagentType")
        ?: "agent"
    val prompt = args.stringField("prompt").orEmpty()
    val runInBackground = args["run_in_background"]?.jsonPrimitive?.booleanOrNull
        ?: args["runInBackground"]?.jsonPrimitive?.booleanOrNull
        ?: false
    val result = returnContent?.let(::parseJsonObject)
    return UiSubagentDispatch(
        toolCallId = toolCallId,
        description = description,
        subagentType = subagentType,
        runInBackground = runInBackground,
        prompt = prompt,
        taskId = result?.stringField("task_id") ?: result?.stringField("taskId"),
        subagentAgentId = result?.stringField("subagent_agent_id")
            ?: result?.stringField("subagentAgentId")
            ?: result?.stringField("agent_id")
            ?: result?.stringField("agentId"),
    )
}

private fun extractSubagentNotification(raw: String): UiSubagentNotification? {
    if (raw.indexOf("<task-notification", ignoreCase = true) < 0) return null
    val blockStart = raw.indexOf("<task-notification", ignoreCase = true)
    val blockEnd = raw.indexOf("</task-notification>", startIndex = blockStart, ignoreCase = true)
    val block = if (blockEnd >= 0) {
        raw.substring(blockStart, blockEnd + "</task-notification>".length)
    } else {
        raw.substring(blockStart)
    }
    val status = block.xmlTag("status")
        ?: block.xmlTag("state")
        ?: "completed"
    return UiSubagentNotification(
        toolCallId = block.xmlTag("tool_call_id") ?: block.xmlTag("toolCallId"),
        status = status,
        summary = block.xmlTag("summary"),
        result = block.xmlTag("result"),
        usage = block.xmlTag("usage"),
        transcriptUri = block.xmlTag("transcript")
            ?: block.lineAfter("Full transcript at")
            ?: block.lineAfter("Full transcript:"),
        taskId = block.xmlTag("task_id") ?: block.xmlTag("taskId"),
        subagentAgentId = block.xmlTag("agent_id") ?: block.xmlTag("agentId"),
    )
}

private fun parseJsonObject(raw: String): JsonObject? =
    runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun String.xmlTag(name: String): String? {
    val open = Regex("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?: return null
    return open
        .removePrefix("<![CDATA[")
        .removeSuffix("]]>")
        .decodeXmlEntities()
        .trim()
        .takeIf { it.isNotBlank() }
}

private fun String.lineAfter(marker: String): String? {
    val index = indexOf(marker, ignoreCase = true)
    if (index < 0) return null
    val start = index + marker.length
    val end = indexOf('\n', start).let { if (it < 0) length else it }
    return substring(start, end)
        .trim()
        .trimStart(':')
        .trim()
        .decodeXmlEntities()
        .takeIf { it.isNotBlank() }
}

private fun String.decodeXmlEntities(): String =
    replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

private fun extractGeneratedUi(raw: kotlinx.serialization.json.JsonElement?): GeneratedUiPayload? {
    val obj = raw as? JsonObject ?: return null
    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null
    if (type != "generated_ui") return null

    val component = obj["component"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val props = obj["props"]
    val propsJson = when (props) {
        null -> buildJsonObject {}.toString()
        else -> props.toString()
    }
    val fallbackText = obj["text"]?.jsonPrimitive?.contentOrNull
        ?: obj["fallback_text"]?.jsonPrimitive?.contentOrNull

    return GeneratedUiPayload(
        component = component,
        propsJson = propsJson,
        fallbackText = fallbackText,
    )
}

private fun extractGeneratedUiFromString(raw: String): GeneratedUiPayload? {
    if (raw.isBlank()) return null
    return runCatching {
        extractGeneratedUi(Json.parseToJsonElement(raw))
    }.getOrNull()
}

private fun String.toInstant(): Instant {
    return try {
        Instant.parse(this)
    } catch (e: Exception) {
        Instant.now()
    }
}
