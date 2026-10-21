package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.chat.projection.meridian.MeridianCommandCall
import com.letta.mobile.data.messaging.AgentMessageProvenanceProjection
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.UiToolResultTruncation
import com.letta.mobile.data.model.extractSubagentDispatch
import com.letta.mobile.data.timeline.TimelineEvent

private fun com.letta.mobile.data.model.ToolCall.toSubagentDispatch(result: String?): UiSubagentDispatch? =
    if (name == "Agent") {
        extractSubagentDispatch(
            toolCallId = effectiveId.takeIf { it.isNotBlank() },
            arguments = arguments.orEmpty(),
            returnContent = result,
        )
    } else {
        null
    }

/**
 * Normalize a skill-invocation tool call for display.
 *
 * When a tool call's arguments contain a `skill` field (possibly wrapped in
 * up to two JSON-string layers), replace the tool name with "Skill" and the
 * remaining arguments as normalized JSON. Unknown or malformed payloads fall
 * back to the original name/arguments so the tool call stays visible.
 */
private data class NormalizedToolCall(
    val name: String,
    val arguments: String,
    val displayTarget: String? = null,
)

private fun normalizeSkillToolCall(name: String, arguments: String): NormalizedToolCall {
    val normalized = com.letta.mobile.data.model.SkillArgumentNormalizer.normalize(arguments)
    return if (normalized != null) {
        // Keep invocation details on the ordinary tool-card path rather than
        // projecting the injected skill document as a second timeline item.
        NormalizedToolCall(
            name = "Skill",
            arguments = normalized.normalizedArguments,
            displayTarget = normalized.skillName,
        )
    } else {
        NormalizedToolCall(name = name, arguments = arguments)
    }
}

/** Event-specific result, approval, image and provenance fields consumed by tool normalization. */
internal data class ToolCallRenderData(
    val result: String?,
    val isError: Boolean,
    val generatedImageAttachments: List<UiImageAttachment>,
    val executionTimeMs: Long? = null,
    val approvalDecision: UiToolApprovalDecision? = null,
    val resultTruncation: UiToolResultTruncation? = null,
    val sourceAgentId: String? = null,
)

/**
 * A `meridian canvas …` call (Bash or the meta-tool) renders as the native
 * canvas tool it runs, its result read off stdout, so its row matches the native call's row.
 */
private fun ToolCallRenderData.forMeridianStdout(): ToolCallRenderData =
    copy(result = MeridianCommandCall.toolResult(result), isError = isError || MeridianCommandCall.isErrorResult(result))

/** Normalize a native, skill or Meridian call without discarding its result or provenance. */
internal fun com.letta.mobile.data.model.ToolCall.toUiToolCall(data: ToolCallRenderData): UiToolCall {
    MeridianCommandCall.parse(this)?.let { meridian -> return meridian.applyTo(this).toNativeUiToolCall(data.forMeridianStdout()) }
    return toNativeUiToolCall(data)
}

private fun com.letta.mobile.data.model.ToolCall.toNativeUiToolCall(data: ToolCallRenderData): UiToolCall {
    val normalized = normalizeSkillToolCall(name ?: "tool", arguments ?: "")
    return UiToolCall(
        name = normalized.name,
        arguments = normalized.arguments,
        result = data.result,
        displayTarget = normalized.displayTarget,
        status = data.result?.let { if (data.isError) "error" else "success" },
        generatedImageAttachments = data.generatedImageAttachments,
        executionTimeMs = data.executionTimeMs,
        toolCallId = effectiveId.takeIf { it.isNotBlank() },
        approvalDecision = data.approvalDecision,
        subagentDispatch = toSubagentDispatch(data.result),
        resultTruncation = data.resultTruncation,
        agentMessageProvenance = AgentMessageProvenanceProjection.projectOutbound(
            toolName = name,
            argumentsJson = arguments,
            resultJson = data.result,
            isError = data.isError,
            fromAgentId = data.sourceAgentId,
        ),
    )
}

/** Attach generated images to the generating call so they are not duplicated as message attachments. */
internal fun com.letta.mobile.data.model.ToolCall.generatedImageAttachments(
    attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>,
): List<UiImageAttachment> = if (name == "generate_image") {
    attachments.map { UiImageAttachment(base64 = it.base64, mediaType = it.mediaType, storedByteSize = it.storedByteSize) }
} else {
    emptyList()
}

/**
 * Approval chip for a TOOL_CALL event. An explicit
 * decision (ApprovalDecision) always wins; REJECTED projects to Rejected so
 * a rejected call no longer renders as Approved. A decided event with no
 * explicit outcome falls to [implicitApprovalChip].
 */
internal fun TimelineEvent.Confirmed.approvalChip(): UiToolApprovalDecision? = when (approvalDecision) {
    com.letta.mobile.data.timeline.ApprovalDecision.REJECTED -> UiToolApprovalDecision.Rejected
    com.letta.mobile.data.timeline.ApprovalDecision.APPROVED -> UiToolApprovalDecision.Approved
    null -> implicitApprovalChip(
        decided = approvalDecided,
        approvalRequestId = approvalRequestId,
        anyToolReturnError = toolReturnIsError || toolReturnIsErrorByCallId.values.any { it },
    )
}
