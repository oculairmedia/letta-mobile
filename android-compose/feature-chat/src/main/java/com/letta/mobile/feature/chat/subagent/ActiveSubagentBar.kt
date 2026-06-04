package com.letta.mobile.feature.chat.subagent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.LettaMotion
import kotlinx.collections.immutable.ImmutableList

/**
 * letta-mobile-73o2h.2: persistent bottom status bar that renders ONLY
 * currently-active subagents. No history. Hidden entirely when none active.
 *
 * Design / perf notes (do NOT regress letta-mobile-rmzmo streaming-jank
 * work):
 *  - The host passes an already-active-only, immutable, snapshot list. This
 *    composable does no per-frame rebuild; it renders straight off the list.
 *  - Chips are keyed by stable [ActiveSubagent.id] so an unchanged subagent
 *    keeps its slot and is NOT recomposed when the set changes. The model is
 *    `@Immutable`, so Compose skips unchanged chips.
 *  - The running spinner animates via `graphicsLayer { rotationZ = ... }`
 *    (draw phase), matching the tool-card spinner fix — no composition-phase
 *    modifier churn that would invalidate layout each frame.
 *  - Reduced-motion: when set, the spinner does not animate (static icon).
 *  - The whole bar is wrapped in [AnimatedVisibility] gated on `isNotEmpty`
 *    so empty -> fully hidden (no residual height).
 *
 * Condensed form: when more than [CONDENSE_THRESHOLD] subagents are active,
 * a single summary chip ("N subagents running") is shown instead of N chips,
 * keeping the bar compact.
 */
@Composable
fun ActiveSubagentBar(
    subagents: ImmutableList<ActiveSubagent>,
    modifier: Modifier = Modifier,
    onChipClick: (ActiveSubagent) -> Unit = {},
) {
    // Defensive active-only filter happens in the host; here we trust the
    // snapshot. Visibility is driven purely by emptiness.
    AnimatedVisibility(
        visible = subagents.isNotEmpty(),
        // Grow up from / shrink down toward the bottom edge (the bar hugs
        // the composer), reusing the shared design-system motion ramp.
        enter = LettaMotion.verticalEnter(expandFrom = Alignment.Bottom),
        exit = LettaMotion.verticalExit(shrinkTowards = Alignment.Bottom),
        modifier = modifier,
    ) {
        // AnimatedVisibility keeps the last composed content mounted for the
        // duration of the exit transition, so we render straight off the
        // current snapshot — no need to cache a "last non-empty" copy.
        val rendered = subagents
        if (rendered.size > CONDENSE_THRESHOLD) {
            CondensedSubagentChip(
                count = rendered.size,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LettaSpacing.screenHorizontal, vertical = LettaSpacing.xs),
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LettaSpacing.chipGap),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = LettaSpacing.screenHorizontal,
                    vertical = LettaSpacing.xs,
                ),
            ) {
                items(items = rendered, key = { it.id }) { subagent ->
                    SubagentChip(
                        subagent = subagent,
                        onClick = { onChipClick(subagent) },
                    )
                }
            }
        }
    }
}

private const val CONDENSE_THRESHOLD = 2

/** A single running-subagent chip: spinner + description + type. */
@Composable
private fun SubagentChip(
    subagent: ActiveSubagent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(LettaSpacing.bubbleRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = LettaSpacing.md, vertical = LettaSpacing.xs)
            .semantics {
                contentDescription = "Running subagent: ${subagent.description}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpacing.sm),
    ) {
        RunningSpinner()
        Text(
            text = subagent.description,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Condensed summary chip shown when many subagents are active. */
@Composable
private fun CondensedSubagentChip(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(LettaSpacing.bubbleRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = LettaSpacing.md, vertical = LettaSpacing.xs)
            .semantics {
                contentDescription = "$count subagents running"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpacing.sm),
    ) {
        RunningSpinner()
        Text(
            text = "$count subagents running",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Running spinner. Rotation is applied in the DRAW phase via
 * `graphicsLayer { rotationZ = ... }` (matching the tool-card spinner fix),
 * NOT via a composition-phase modifier update — so the animation does not
 * invalidate layout/composition every frame. Respects reduced-motion by
 * rendering a static icon.
 */
@Composable
private fun RunningSpinner(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotionEnabled()
    if (reducedMotion) {
        Icon(
            imageVector = LettaIcons.Refresh,
            contentDescription = null,
            modifier = modifier.size(LettaIconSizing.Inline),
            tint = MaterialTheme.colorScheme.primary,
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "subagentSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "subagentSpinAngle",
    )
    Icon(
        imageVector = LettaIcons.Refresh,
        contentDescription = null,
        modifier = modifier
            .size(LettaIconSizing.Inline)
            .graphicsLayer { rotationZ = angle },
        tint = MaterialTheme.colorScheme.primary,
    )
}

@Preview(name = "Single active")
@Composable
private fun ActiveSubagentBarSinglePreview() {
    LettaChatTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            ActiveSubagentBar(
                subagents = FakeActiveSubagentSource.sample(1).activeSubagents.value,
            )
        }
    }
}

@Preview(name = "Two active")
@Composable
private fun ActiveSubagentBarTwoPreview() {
    LettaChatTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            ActiveSubagentBar(
                subagents = FakeActiveSubagentSource.sample(2).activeSubagents.value,
            )
        }
    }
}

@Preview(name = "Condensed (4 active)")
@Composable
private fun ActiveSubagentBarCondensedPreview() {
    LettaChatTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            ActiveSubagentBar(
                subagents = FakeActiveSubagentSource.sample(4).activeSubagents.value,
            )
        }
    }
}
