package com.letta.mobile.feature.chat.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.chat.projection.StepDotIcon
import com.letta.mobile.data.chat.projection.runStepDotIcon
import com.letta.mobile.data.model.UiMessage

/**
 * Resolve the Material color scheme slot used to tint the gutter dot for
 * [this] message. Composable so it can read the active [MaterialTheme].
 */
@Composable
@ReadOnlyComposable
internal fun UiMessage.runStepDotColor(): Color = when (runStepDotIcon()) {
    StepDotIcon.Reasoning -> MaterialTheme.colorScheme.tertiary
    StepDotIcon.ToolCall -> MaterialTheme.colorScheme.primary
    StepDotIcon.Approval -> MaterialTheme.colorScheme.secondary
    StepDotIcon.AssistantText -> MaterialTheme.colorScheme.onSurfaceVariant
    StepDotIcon.Unknown -> MaterialTheme.colorScheme.outline
}
