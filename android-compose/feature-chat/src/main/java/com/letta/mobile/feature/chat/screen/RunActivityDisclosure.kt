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
    collapsible: Boolean = true,
) {
    val text = activity.disclosureText(collapsed, collapsible)
    val canToggle = collapsible && !activity.isActive

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(RunActivityDisclosureTestTags.Header)
            .heightIn(min = 48.dp)
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
            text = text.title,
            style = MaterialTheme.typography.labelMedium,
            color = if (activity.isActive) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        ActivityCounts(activity)
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
private fun ActivityCounts(activity: RunActivityProjection) {
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
