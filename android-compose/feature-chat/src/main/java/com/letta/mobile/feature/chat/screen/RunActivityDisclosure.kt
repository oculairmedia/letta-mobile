package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.RunActivityState
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.preview.LettaPreviewFrame

internal object RunActivityDisclosureTestTags {
    val Header = "run-activity-disclosure"
    val WorkingIndicator = "run-activity-working-indicator"
}

@Composable
internal fun RunActivityDisclosure(
    activity: RunActivityProjection,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    collapsible: Boolean = true,
    chatMode: String = "interactive",
) {
    val text = activity.disclosureText(collapsed, collapsible)
    val canToggle = collapsible && !activity.isActive
    val isSimpleMode = chatMode == "simple"

    // Simple mode uses more compact rendering: smaller padding, smaller fonts,
    // and no minimum height constraint to reduce visual prominence.
    val horizontalPadding = if (isSimpleMode) 2.dp else 4.dp
    val verticalPadding = 2.dp
    val minHeight = if (canToggle) 44.dp else if (isSimpleMode) 24.dp else 32.dp
    val iconSize = if (isSimpleMode) 14.dp else 16.dp
    val textStyle = if (isSimpleMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    val spacing = if (isSimpleMode) 4.dp else 6.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RunActivityDisclosureTestTags.Header)
            .heightIn(min = minHeight)
            .semantics(mergeDescendants = true) {
                text.stateDescription?.let { stateDescription = it }
            }
            .then(
                if (canToggle) {
                    Modifier.clickable(
                        onClickLabel = text.interactionLabel,
                        onClick = onToggleCollapsed,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        if (activity.isActive) {
            WorkingIndicator()
        } else if (collapsible) {
            Icon(
                imageVector = LettaIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(iconSize)
                    .rotate(if (collapsed) 0f else 180f),
            )
        }
        Text(
            text = text.title,
            style = textStyle,
            color = if (activity.isActive) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        ActivityCounts(activity, isSimpleMode = isSimpleMode)
    }
}

private data class DisclosureText(
    val title: String,
    val interactionLabel: String,
    val stateDescription: String?,
)

@Composable
private fun RunActivityProjection.disclosureText(
    collapsed: Boolean,
    collapsible: Boolean,
): DisclosureText {
    val duration = durationMs?.let(::formatToolExecutionTime)
    return DisclosureText(
        title = activityTitle(duration),
        interactionLabel = stringResource(
            if (collapsed) R.string.work_disclosure_expand else R.string.work_disclosure_collapse,
        ),
        stateDescription = stateDescriptionResource(collapsed, collapsible)?.let { stringResource(it) },
    )
}

@Composable
private fun RunActivityProjection.activityTitle(duration: String?): String = when (state) {
    RunActivityState.Working -> stringResource(R.string.work_disclosure_working)
    RunActivityState.Thought -> duration?.let {
        stringResource(R.string.work_disclosure_thought_duration, it)
    } ?: stringResource(R.string.work_disclosure_thought)
    RunActivityState.Worked -> duration?.let {
        stringResource(R.string.work_disclosure_worked_duration, it)
    } ?: stringResource(R.string.work_disclosure_worked)
}

private fun RunActivityProjection.stateDescriptionResource(
    collapsed: Boolean,
    collapsible: Boolean,
): Int? = when {
    isActive -> R.string.work_disclosure_state_working
    collapsible && collapsed -> R.string.work_disclosure_state_collapsed
    collapsible -> R.string.work_disclosure_state_expanded
    else -> null
}

@Composable
private fun ActivityCounts(activity: RunActivityProjection, isSimpleMode: Boolean = false) {
    val countStyle = if (isSimpleMode) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelSmall
    if (activity.toolCount > 0) {
        Text(
            text = pluralStringResource(
                R.plurals.work_disclosure_tool_count,
                activity.toolCount,
                activity.toolCount,
            ),
            style = countStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.76f),
        )
    }
    if (activity.failureCount > 0) {
        Text(
            text = pluralStringResource(
                R.plurals.work_disclosure_failure_count,
                activity.failureCount,
                activity.failureCount,
            ),
            style = countStyle,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * A single draw-layer cue for active agent work.
 *
 * The indicator owns fixed layout bounds and only changes compositor alpha, so
 * streaming updates cannot move the disclosure header or its timeline parent.
 * It is decorative: the header's state description carries the accessible
 * progress state without repeatedly announcing an animation.
 */
@Composable
private fun WorkingIndicator(
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val indicatorAlpha: State<Float> = if (reducedMotion) {
        remember { mutableFloatStateOf(WorkingIndicatorRestingAlpha) }
    } else {
        val transition = rememberInfiniteTransition(label = "agentWorkCue")
        transition.animateFloat(
            initialValue = WorkingIndicatorDimAlpha,
            targetValue = WorkingIndicatorBrightAlpha,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = WorkingIndicatorPulseDurationMillis,
                    easing = FastOutSlowInEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "agentWorkCueAlpha",
        )
    }

    Box(
        modifier = modifier
            .testTag(RunActivityDisclosureTestTags.WorkingIndicator)
            .size(6.dp)
            .graphicsLayer {
                alpha = indicatorAlpha.value
            }
            .background(
                color = MaterialTheme.colorScheme.tertiary,
                shape = androidx.compose.foundation.shape.CircleShape,
            ),
    )
}

private const val WorkingIndicatorDimAlpha = 0.44f
private const val WorkingIndicatorRestingAlpha = 0.72f
private const val WorkingIndicatorBrightAlpha = 0.92f
private const val WorkingIndicatorPulseDurationMillis = 1_400

// region Previews

private data class PreviewRunActivityConfig(
    val durationMs: Long? = null,
    val toolCount: Int = 0,
    val failureCount: Int = 0,
)

private fun previewRunActivity(
    state: RunActivityState,
    config: PreviewRunActivityConfig = PreviewRunActivityConfig(),
): RunActivityProjection = RunActivityProjection(
    state = state,
    durationMs = config.durationMs,
    toolCount = config.toolCount,
    failureCount = config.failureCount,
)

private data class PreviewRunActivityDisclosureState(
    val state: RunActivityState,
    val durationMs: Long?,
    val toolCount: Int,
    val failureCount: Int,
    val collapsed: Boolean,
    val chatMode: String,
)

@Composable
private fun PreviewRunActivityDisclosure(state: PreviewRunActivityDisclosureState) {
    LettaPreviewFrame {
        RunActivityDisclosure(
            activity = previewRunActivity(
                state = state.state,
                config = PreviewRunActivityConfig(
                    durationMs = state.durationMs,
                    toolCount = state.toolCount,
                    failureCount = state.failureCount,
                ),
            ),
            collapsed = state.collapsed,
            onToggleCollapsed = {},
            chatMode = state.chatMode,
        )
    }
}

private val previewWorkingState = PreviewRunActivityDisclosureState(
    state = RunActivityState.Working,
    durationMs = null,
    toolCount = 0,
    failureCount = 0,
    collapsed = false,
    chatMode = "interactive",
)

private val previewThoughtState = PreviewRunActivityDisclosureState(
    state = RunActivityState.Thought,
    durationMs = 2_400,
    toolCount = 3,
    failureCount = 0,
    collapsed = false,
    chatMode = "interactive",
)

private val previewCollapsedState = PreviewRunActivityDisclosureState(
    state = RunActivityState.Worked,
    durationMs = 8_200,
    toolCount = 5,
    failureCount = 1,
    collapsed = true,
    chatMode = "simple",
)

@PreviewLightDark
@Composable
private fun RunActivityDisclosureWorkingPreview() {
    RunActivityDisclosurePreviewByState(previewWorkingState)
}

@PreviewLightDark
@Composable
private fun RunActivityDisclosureThoughtPreview() {
    RunActivityDisclosurePreviewByState(previewThoughtState)
}

@PreviewLightDark
@Composable
private fun RunActivityDisclosureCollapsedPreview() {
    RunActivityDisclosurePreviewByState(previewCollapsedState)
}

@Composable
private fun RunActivityDisclosurePreviewByState(state: PreviewRunActivityDisclosureState) {
    PreviewRunActivityDisclosure(state)
}

// endregion
