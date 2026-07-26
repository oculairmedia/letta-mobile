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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.icons.LettaIcons

internal object RunActivityDisclosureTestTags {
    const val Header = "run-activity-disclosure"
    const val WorkingIndicator = "run-activity-working-indicator"
}

@Composable
internal fun RunActivityDisclosure(
    activity: RunActivityProjection,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    modifier: Modifier = Modifier,
    collapsible: Boolean = true,
) {
    val duration = activity.durationMs?.let(::formatToolExecutionTime)
    val title = when (activity.state) {
        RunActivityState.Working -> stringResource(R.string.work_disclosure_working)
        RunActivityState.Thought -> if (duration == null) {
            stringResource(R.string.work_disclosure_thought)
        } else {
            stringResource(R.string.work_disclosure_thought_duration, duration)
        }
        RunActivityState.Worked -> if (duration == null) {
            stringResource(R.string.work_disclosure_worked)
        } else {
            stringResource(R.string.work_disclosure_worked_duration, duration)
        }
    }
    val interactionLabel = if (collapsed) {
        stringResource(R.string.work_disclosure_expand)
    } else {
        stringResource(R.string.work_disclosure_collapse)
    }
    val disclosureState = when {
        activity.isActive -> stringResource(R.string.work_disclosure_state_working)
        collapsible && collapsed -> stringResource(R.string.work_disclosure_state_collapsed)
        else -> stringResource(R.string.work_disclosure_state_expanded)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(RunActivityDisclosureTestTags.Header)
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = disclosureState
            }
            .clickable(
                enabled = collapsible && !activity.isActive,
                onClickLabel = interactionLabel,
                onClick = onToggleCollapsed,
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (activity.isActive) {
            WorkingIndicator()
        } else if (collapsible) {
            Icon(
                imageVector = LettaIcons.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (collapsed) 0f else 180f),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = if (activity.isActive) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (activity.toolCount > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.work_disclosure_tool_count,
                    activity.toolCount,
                    activity.toolCount,
                ),
                style = MaterialTheme.typography.labelSmall,
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
    val indicatorAlpha = if (reducedMotion) {
        WorkingIndicatorRestingAlpha
    } else {
        val transition = rememberInfiniteTransition(label = "agentWorkCue")
        val animatedAlpha by transition.animateFloat(
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
        animatedAlpha
    }

    Box(
        modifier = modifier
            .testTag(RunActivityDisclosureTestTags.WorkingIndicator)
            .size(6.dp)
            .graphicsLayer {
                alpha = indicatorAlpha
            }
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = androidx.compose.foundation.shape.CircleShape,
            ),
    )
}

private const val WorkingIndicatorDimAlpha = 0.44f
private const val WorkingIndicatorRestingAlpha = 0.72f
private const val WorkingIndicatorBrightAlpha = 0.92f
private const val WorkingIndicatorPulseDurationMillis = 1_400
