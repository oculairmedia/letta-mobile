package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage

/**
 * Visual classification for the per-step "dot" rendered in a run timeline
 * gutter. Theme/color resolution stays platform-side; this pure classification
 * is shared by Android and desktop renderers.
 */
enum class StepDotIcon {
    /** Assistant chain-of-thought message ([UiMessage.isReasoning] == true). */
    Reasoning,

    /** Assistant message that issued one or more tool calls. */
    ToolCall,

    /** Assistant message asking the user to approve a tool call. */
    Approval,

    /** Plain assistant prose / final response. */
    AssistantText,

    /** Anything we don't recognise; keeps the gutter visually consistent. */
    Unknown,
}

fun UiMessage.runStepDotIcon(): StepDotIcon = when {
    isReasoning -> StepDotIcon.Reasoning
    approvalRequest != null -> StepDotIcon.Approval
    !toolCalls.isNullOrEmpty() -> StepDotIcon.ToolCall
    role == "assistant" -> StepDotIcon.AssistantText
    else -> StepDotIcon.Unknown
}
