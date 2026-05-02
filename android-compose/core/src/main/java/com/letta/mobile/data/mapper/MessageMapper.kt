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
import com.letta.mobile.data.model.UiToolCall
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        if (msg.messageType == MessageType.TOOL_RETURN && !msg.toolCallId.isNullOrBlank()) {
            returnsByCallId[msg.toolCallId] = msg
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

                val toolCall = UiToolCall(
                    name = name ?: "tool",
                    arguments = arguments,
                    result = returnContent,
                    status = returnStatus,
                    executionTimeMs = executionTimeMs,
                    approvalDecision = msg.toolCallId?.let { foldedApprovals[it]?.decision },
                )
                result.add(UiMessage(
                    id = msg.id,
                    role = "tool",
                    content = "",
                    timestamp = msg.date.toString(),
                    runId = msg.runId,
                    stepId = msg.stepId,
                    toolCalls = listOf(toolCall),
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

                val toolCall = UiToolCall(
                    name = name,
                    arguments = "",
                    result = msg.content.ifBlank { null },
                    status = msg.toolReturnStatus,
                    approvalDecision = msg.toolCallId?.let { foldedApprovals[it]?.decision },
                )
                result.add(UiMessage(
                    id = msg.id,
                    role = "tool",
                    content = "",
                    timestamp = msg.date.toString(),
                    runId = msg.runId,
                    stepId = msg.stepId,
                    toolCalls = listOf(toolCall),
                ))
            }

            MessageType.USER -> result.add(msg.toUiMessage())
            MessageType.ASSISTANT -> result.add(msg.toUiMessage())
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
    val role = when (messageType) {
        MessageType.USER -> "user"
        MessageType.ASSISTANT -> "assistant"
        MessageType.REASONING -> "assistant"
        MessageType.TOOL_CALL -> "tool"
        MessageType.TOOL_RETURN -> "tool"
        MessageType.APPROVAL_REQUEST -> "approval"
        MessageType.APPROVAL_RESPONSE -> "approval"
    }
    val toolCalls = when {
        messageType == MessageType.TOOL_CALL -> {
            listOf(UiToolCall(name = toolName ?: "tool", arguments = content, result = null))
        }
        messageType == MessageType.TOOL_RETURN -> {
            listOf(UiToolCall(name = toolName ?: "tool", arguments = "", result = content.ifBlank { null }))
        }
        else -> null
    }
    val displayContent = when {
        messageType == MessageType.TOOL_CALL -> ""
        messageType == MessageType.TOOL_RETURN -> ""
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
        // letta-mobile-mge5.24: surface inline image parts extracted during
        // AppMessage mapping. USER/ASSISTANT bubbles will render a
        // MessageAttachmentsGrid alongside the text content.
        attachments = attachments.map {
            UiImageAttachment(base64 = it.base64, mediaType = it.mediaType)
        },
    )
}

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
