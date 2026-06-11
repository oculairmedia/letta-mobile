package com.letta.mobile.feature.chat.subagent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaMotion
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.customColors
import kotlinx.collections.immutable.ImmutableList

/**
 * letta-mobile-w8mog: CIRCULAR progress rings stacked on the RIGHT edge of the
 * chat surface. REPLACES the ActiveSubagentBar chip presentation.
 *
 * LIFECYCLE (consolidated from xm8qk + q6v8b + od488):
 *  - BORN: ring appears with indeterminate accent-token spin (theme-aware
 *    primary, NOT hardcoded), small tool/role glyph or initial in center
 *  - RUNNING: tap → navigate to the subagent's conversation if it has one
 *    (FIX: uses SubagentEntry.subagentAgentId + default conversation, NOT the
 *    parent conversation); fall back to the todo sheet when no conversation
 *    exists
 *  - COMPLETED: ring collapses away (silent success — NO lingering green
 *    checkmark) and the result graduates into a chat message in the parent
 *    conversation timeline
 *  - FAILED: ring turns red (error color token), persists until
 *    tapped/acknowledged
 *  - Overflow: >3 rings → show 3 + a '+N' count badge
 *
 * HARD CONSTRAINT (Emmanuel): NO bot/robot iconography anywhere — ring centers
 * are abstract or task-derived (tool glyph, role initial, or plain ring).
 * Applies to badges and empty/error states.
 *
 * DESIGN DISCIPLINE (Pencil-spec):
 *  - 8px radius geometry family, 1px outlines, accent used sparingly
 *  - Spin animation must be a cheap graphicsLayer rotation, NOT
 *    recomposition-driven; respect reduced-motion settings
 *  - Render hot-path rules: no per-frame work in composition bodies,
 *    remember() everything computed, react to discrete events O(delta)
 *
 * The data layer (SubagentRepository/SubagentEntry/WsActiveSubagentSource) is
 * UNCHANGED — this is a presentation swap only. The stuck detection, lingering
 * terminals, and condensed view logic from ActiveSubagentBar carries over.
 */
@Composable
fun ActiveSubagentRings(
    subagents: ImmutableList<ActiveSubagent>,
    modifier: Modifier = Modifier,
    onRingClick: (ActiveSubagent) -> Unit = {},
    onViewConversation: (ActiveSubagent) -> Unit = {},
    now: Long = System.currentTimeMillis(),
) {
    // letta-mobile-w8mog: COMPLETED rings collapse away silently (no lingering
    // green checkmark). Only RUNNING and FAILED rings are shown.
    val visibleSubagents = subagents.filter { subagent ->
        subagent.status == ActiveSubagent.Status.RUNNING ||
        subagent.status == ActiveSubagent.Status.FAILED
    }

    AnimatedVisibility(
        visible = visibleSubagents.isNotEmpty(),
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(LettaSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(LettaSpacing.xs),
            horizontalAlignment = Alignment.End,
        ) {
            // letta-mobile-w8mog: overflow constraint — >3 rings → show 3 + a
            // '+N' count badge
            val maxRings = 3
            val displayedRings = visibleSubagents.take(maxRings)
            val overflow = (visibleSubagents.size - maxRings).coerceAtLeast(0)

            displayedRings.forEach { subagent ->
                ActivityRing(
                    subagent = subagent,
                    now = now,
                    onClick = {
                        // letta-mobile-xm8qk: tap must navigate to the correct
                        // conversation (subagent's conversation if it has one,
                        // or open the todo sheet when no conversation exists)
                        if (subagent.canViewConversation) {
                            onViewConversation(subagent)
                        } else {
                            onRingClick(subagent)
                        }
                    },
                )
            }

            if (overflow > 0) {
                OverflowBadge(count = overflow)
            }
        }
    }
}

/**
 * letta-mobile-w8mog: single circular progress ring. Shows indeterminate spin
 * while running (accent color), turns red on failure. Center contains a
 * tool/role glyph or initial (NO bot/robot iconography per Emmanuel).
 */
@Composable
private fun ActivityRing(
    subagent: ActiveSubagent,
    now: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val ringState = subagent.ringState(now)
    val color = ringColor(ringState)
    val glyph = subagent.kindGlyph()

    // Indeterminate rotation animation (only when RUNNING and motion enabled)
    val rotation = if (
        subagent.status == ActiveSubagent.Status.RUNNING && !reducedMotion
    ) {
        val transition = rememberInfiniteTransition(label = "ringRotation")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ringRotationAngle",
        )
        angle
    } else {
        0f
    }

    val ringSize = 48.dp
    val strokeWidth = 2.dp

    Box(
        modifier = modifier
            .size(ringSize)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = subagent.chipSemanticLabel()
            },
        contentAlignment = Alignment.Center,
    ) {
        // Background circle
        Canvas(
            modifier = Modifier
                .size(ringSize)
                .graphicsLayer { rotationZ = rotation },
        ) {
            val strokePx = strokeWidth.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(
                size.width - strokePx,
                size.height - strokePx,
            )
            val topLeft = Offset(inset, inset)

            // Track ring (faint outline)
            drawArc(
                color = color.copy(alpha = 0.25f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )

            // letta-mobile-w8mog: indeterminate arc (90° comet) for running
            // rings; static full ring for failed rings
            val sweep = if (subagent.status == ActiveSubagent.Status.RUNNING) {
                90f
            } else {
                360f
            }

            drawArc(
                color = color,
                startAngle = -90f, // 12 o'clock
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }

        // Center glyph (NO bot/robot iconography per Emmanuel)
        Icon(
            imageVector = glyph,
            contentDescription = null,
            modifier = Modifier.size(LettaIconSizing.Inline),
            tint = color,
        )
    }
}

/**
 * letta-mobile-w8mog: overflow count badge shown when >3 rings are active.
 * Shows "+N" in a compact circular badge. NO bot/robot iconography.
 */
@Composable
private fun OverflowBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = "$count more subagents active"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * letta-mobile-w8mog: map a [RingState] to its design-system tint.
 *  - RUNNING -> theme primary (accent token)
 *  - STUCK -> warning yellow
 *  - ERROR -> error red
 *  - SUCCESS -> (not shown — completed rings collapse away)
 */
@Composable
private fun ringColor(state: RingState): Color = when (state) {
    RingState.RUNNING -> MaterialTheme.colorScheme.primary
    RingState.STUCK -> MaterialTheme.customColors.warningTextColor
        .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.tertiary
    RingState.ERROR -> MaterialTheme.customColors.errorTextColor
        .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.error
    RingState.SUCCESS -> MaterialTheme.colorScheme.primary // Not used
}

/**
 * letta-mobile-w8mog: the glyph that differentiates the ring kind. NO
 * bot/robot iconography per Emmanuel — tool glyph, role initial, or plain
 * ring only.
 */
private fun ActiveSubagent.kindGlyph(): ImageVector = when (kind) {
    // A tool task is NOT an agent — distinct glyph (a wrench) + label.
    ActiveSubagent.Kind.BACKGROUND_TASK -> LettaIcons.Tool
    // letta-mobile-w8mog: NO Agent icon (bot/robot iconography). Use a
    // generic activity indicator instead. For now, reuse Tool icon as a
    // placeholder for abstract glyph.
    ActiveSubagent.Kind.SELF -> LettaIcons.Tool
    ActiveSubagent.Kind.SUBAGENT -> LettaIcons.Tool
}

/**
 * Unambiguous accessibility label — never "completed" for a running entry.
 * Copied from ActiveSubagentBar for consistency.
 */
private fun ActiveSubagent.chipSemanticLabel(): String =
    "${statusLabel}: ${description.ifBlank { "(no description)" }}"
