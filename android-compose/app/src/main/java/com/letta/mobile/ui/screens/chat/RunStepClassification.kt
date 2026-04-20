package com.letta.mobile.ui.screens.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.model.UiMessage

/**
 * Visual classification for the per-step "dot" rendered in the run timeline
 * gutter alongside an assistant message inside a [ChatRenderItem.RunBlock].
 *
 * Each [UiMessage] inside a run is classified into one of these categories by
 * [runStepDotIcon]; [runStepDotColor] then maps the icon to a Material color
 * scheme slot so the visual treatment stays in sync with the active theme.
 *
 * Pure / no Compose scope required for the classification itself — this keeps
 * it cheap to test on the JVM (see `RunStepDotIconTest`). Color resolution is
 * a thin Composable adapter on top.
 *
 * letta-mobile-m772.5
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

    /** Anything we don't recognise — keeps the gutter visually consistent. */
    Unknown,
}

/**
 * Classify [this] for the run-timeline gutter. Pure function, safe to call
 * outside a Compose scope.
 */
fun UiMessage.runStepDotIcon(): StepDotIcon = when {
    isReasoning -> StepDotIcon.Reasoning
    approvalRequest != null -> StepDotIcon.Approval
    !toolCalls.isNullOrEmpty() -> StepDotIcon.ToolCall
    role == "assistant" -> StepDotIcon.AssistantText
    else -> StepDotIcon.Unknown
}

/**
 * Resolve the Material color scheme slot used to tint the gutter dot for
 * [this] message. Composable so it can read the active [MaterialTheme].
 */
@Composable
@ReadOnlyComposable
fun UiMessage.runStepDotColor(): Color = when (runStepDotIcon()) {
    StepDotIcon.Reasoning -> MaterialTheme.colorScheme.tertiary
    StepDotIcon.ToolCall -> MaterialTheme.colorScheme.primary
    StepDotIcon.Approval -> MaterialTheme.colorScheme.secondary
    StepDotIcon.AssistantText -> MaterialTheme.colorScheme.onSurfaceVariant
    StepDotIcon.Unknown -> MaterialTheme.colorScheme.outline
}
