package com.letta.mobile.feature.chat.subagent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.theme.LettaMotion
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.customColors
import kotlinx.collections.immutable.ImmutableList

/**
 * letta-mobile-w8mog + i2f23: CIRCULAR progress rings stacked on the RIGHT
 * edge of the chat surface with DETERMINATE fill driven by per-subagent
 * `todo_progress`. REPLACES the ActiveSubagentBar chip presentation.
 *
 * LIFECYCLE (consolidated from xm8qk + q6v8b + od488 + i2f23):
 *  - BORN: ring appears with a small "started" sliver (~8% arc). When
 *    todo_progress arrives the ring transitions to determinate fill
 *    (0→360° sweep tracked to completed/total).
 *  - RUNNING: tap → open TodoWrite sheet; long press → navigate to the
 *    subagent's own conversation only when the actual
 *    SubagentEntry.subagentConversationId is present or resolved. Missing ids
 *    fall back to the TodoWrite sheet; mobile never guesses parent/default.
 *  - COMPLETED: animate fill to 100% briefly (~300ms) then collapse away
 *    (silent success — NO lingering green checkmark)
 *  - FAILED: ring turns red (error color token), FROZEN at whatever fill it
 *    reached, persists until tapped/acknowledged
 *  - Overflow: >3 rings → show 3 + a '+N' count badge
 *
 * HARD CONSTRAINT (Emmanuel): NO center iconography anywhere — no tool glyph,
 * bot glyph, role glyph, or initials. Rings are progress circles only.
 * Applies to badges and empty/error states.
 *
 * DESIGN DISCIPLINE (Pencil-spec):
 *  - 8px radius geometry family, 1px outlines, accent used sparingly
 *  - Fill fraction animates via animateFloatAsState (cheap), drawn in
 *    Canvas/drawArc — no per-frame recomposition of the item tree, no
 *    allocation in draw
 *  - Sliver pulse (arc-length only, NO rotation) respects reduced-motion
 *  - Render hot-path rules: no per-frame work in composition bodies,
 *    remember() everything computed, react to discrete events O(delta)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActiveSubagentRings(
    subagents: ImmutableList<ActiveSubagent>,
    modifier: Modifier = Modifier,
    onRingClick: (ActiveSubagent) -> Unit = {},
    onViewConversation: (ActiveSubagent) -> Unit = {},
    now: Long = System.currentTimeMillis(),
) {
    // letta-mobile-i2f23: briefly hold COMPLETED rings so the fill animates
    // to 100% (~300ms tween) before the ring collapses away. RUNNING and
    // FAILED rings are always visible.
    val completedRingHolds = remember { mutableStateMapOf<String, Long>() }

    SideEffect {
        // Register new completions
        for (s in subagents) {
            if (s.status == ActiveSubagent.Status.COMPLETED && s.id !in completedRingHolds) {
                completedRingHolds[s.id] = now
            }
        }
        // Clear expired holds
        completedRingHolds.entries.removeAll { (_, ts) ->
            now - ts > COMPLETED_FILL_LINGER_MS
        }
    }

    val visibleSubagents = subagents.filter { subagent ->
        when (subagent.status) {
            ActiveSubagent.Status.RUNNING -> true
            ActiveSubagent.Status.FAILED -> true
            ActiveSubagent.Status.COMPLETED -> completedRingHolds.containsKey(subagent.id)
        }
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
                        onRingClick(subagent)
                    },
                    onLongClick = {
                        if (subagent.canViewConversation) {
                            onViewConversation(subagent)
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
 * letta-mobile-i2f23: single circular progress ring with DETERMINATE fill.
 *
 * Ring sweep = completedTodos / totalTodos, animated smoothly via
 * [animateFloatAsState] between updates. When no todo_progress has arrived
 * yet the ring shows a small (~8%) "started" sliver with an optional slow
 * arc-LENGTH pulse (NO rotation). FAILED rings are frozen at whatever fill
 * they reached; COMPLETED rings briefly animate to 100% before collapsing.
 */
@Composable
private fun ActivityRing(
    subagent: ActiveSubagent,
    now: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val ringState = subagent.ringState(now)
    val color = ringColor(ringState)

    // ── Determinate sweep angle ──────────────────────────────────────────
    // i2f23: sweep tracks the TodoWrite completion fraction. The target
    // sweep is cheap to compute; animateFloatAsState handles the smooth
    // interpolation between updates with zero per-frame allocs.
    val targetSweep = when {
        subagent.status == ActiveSubagent.Status.COMPLETED -> COMPLETED_SWEEP
        subagent.status == ActiveSubagent.Status.FAILED -> {
            // Frozen at whatever fill it reached — ringFraction already
            // captures the last-known progress.
            subagent.ringFraction * COMPLETED_SWEEP
        }
        subagent.hasDeterminateProgress -> {
            subagent.ringFraction * COMPLETED_SWEEP
        }
        else -> SLIVER_SWEEP
    }

    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(
            durationMillis = RING_FILL_ANIMATION_MS,
            easing = LinearEasing,
        ),
        label = "ringSweep",
    )

    // ── Sliver arc‑length pulse (no rotation) ────────────────────────────
    // i2f23: when there are no todos yet, the started sliver gently pulses
    // its arc length (e.g. 8%↔12%) to signal activity. Respects reduced
    // motion (static sliver). NO rotation — this is a sweep-length pulse,
    // not a spin.
    val sliverPulseSweep: Float? = if (
        !subagent.hasDeterminateProgress &&
        subagent.status == ActiveSubagent.Status.RUNNING &&
        !reducedMotion
    ) {
        val transition = rememberInfiniteTransition(label = "sliverPulse")
        transition.animateFloat(
            initialValue = SLIVER_SWEEP,
            targetValue = SLIVER_SWEEP_MAX,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = SLIVER_PULSE_MS,
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "sliverPulseSweep",
        ).value
    } else {
        null
    }

    // The sweep to draw: animated for determinate, possibly pulsing for
    // indeterminate sliver. No rotation — the arc is drawn from 12 o'clock
    // (-90°) with variable sweep length.
    val drawSweep = sliverPulseSweep ?: animatedSweep

    val touchTargetSize = 48.dp
    val ringSize = 36.dp
    val strokeWidth = 4.dp

    Box(
        modifier = modifier
            .size(touchTargetSize)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                contentDescription = subagent.chipSemanticLabel()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier.size(ringSize),
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

            // Determinate / sliver arc — drawn from 12 o'clock (-90°) with
            // the animated/pulsing sweep angle. No graphicsLayer rotation.
            drawArc(
                color = color,
                startAngle = -90f, // 12 o'clock
                sweepAngle = drawSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
        }

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
            .size(36.dp)
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
 * Unambiguous accessibility label — never "completed" for a running entry.
 * Copied from ActiveSubagentBar for consistency.
 */
private fun ActiveSubagent.chipSemanticLabel(): String =
    "${statusLabel}: ${description.ifBlank { "(no description)" }}"

// ── i2f23 constants ──────────────────────────────────────────────────────

/**
 * letta-mobile-i2f23: how long a COMPLETED ring stays visible so its fill can
 * animate from the reached fraction to 100% (~300ms tween) before the
 * collapse animation. After this window the ring is removed from the visible
 * set and AnimatedVisibility collapses it out.
 */
internal const val COMPLETED_FILL_LINGER_MS: Long = 450L

/** 8% arc for the "started" sliver when no todo_progress has arrived yet. */
internal const val SLIVER_SWEEP: Float = 28.8f // ~8% of 360°

/** Max sliver arc during the length pulse (~12%). */
internal const val SLIVER_SWEEP_MAX: Float = 43.2f // ~12% of 360°

/** Pulse period for the sliver arc-length animation (~1.5s per cycle). */
internal const val SLIVER_PULSE_MS: Int = 1500

/** 360° — a full ring (used for completed/100% fill). */
internal const val COMPLETED_SWEEP: Float = 360f

/**
 * Duration of the tween animation when transitioning between determinate
 * fill fractions. Kept short (300ms) for responsive feel; the completed
 * hold window ([COMPLETED_FILL_LINGER_MS]) accounts for the animation + a
 * brief grace period before the collapse exit.
 */
internal const val RING_FILL_ANIMATION_MS: Int = 300
