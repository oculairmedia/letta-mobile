package com.letta.mobile.feature.chat.subagent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.letta.mobile.ui.theme.customColors
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
 *
 * letta-mobile-29h9u — lingering terminals: the list may contain
 * recently-terminal entries (the host applies [withLingeringTerminals]); such
 * chips render in a success (green check) / failed (red error) style instead
 * of the running spinner, so a completed/failed outcome is reviewable before
 * it dismisses. Terminal chips are never folded into the condensed summary
 * (only the running count condenses).
 *
 * letta-mobile-vo9y1 — view conversation: when [onViewConversation] is
 * provided and a chip [ActiveSubagent.canViewConversation], the chip exposes
 * a "view conversation" affordance that jumps to that subagent's transcript.
 */
@Composable
fun ActiveSubagentBar(
    subagents: ImmutableList<ActiveSubagent>,
    modifier: Modifier = Modifier,
    onChipClick: (ActiveSubagent) -> Unit = {},
    onViewConversation: (ActiveSubagent) -> Unit = {},
    // letta-mobile-dvobc: wall-clock epoch-ms used to evaluate the "stuck"
    // ring heuristic (running but no todo-state change for ~30-45s). The host
    // re-evaluates it off the same coarse 1s linger tick, so the ring flips to
    // yellow without any new WS frame. Defaults to the system clock for
    // previews/tests that don't drive a tick.
    now: Long = System.currentTimeMillis(),
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
        // letta-mobile-gnyf7: the self entry (the MAIN agent's OWN plan) is
        // ALWAYS pinned at the head and never folded into the condensed
        // summary; only the dispatched-subagent count condenses behind it.
        val selfEntry = rendered.firstOrNull { it.isSelf }
        val subagentEntries = rendered.filterNot { it.isSelf }
        // letta-mobile-29h9u / pvrrm: terminal (lingering) chips, AND running
        // background-task chips, always render individually — only the running
        // *subagent* set condenses behind the count summary. Background tasks
        // are long-running glances the user explicitly wants to see, so they
        // never fold away.
        val runningSubagents = subagentEntries.filter {
            it.isActive && it.kind == ActiveSubagent.Kind.SUBAGENT
        }
        val runningBackgroundTasks = subagentEntries.filter {
            it.isActive && it.kind == ActiveSubagent.Kind.BACKGROUND_TASK
        }
        val terminalEntries = subagentEntries.filter { it.isTerminal }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LettaSpacing.CHIP_GAP),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = LettaSpacing.SCREEN_HORIZONTAL,
                vertical = LettaSpacing.XS,
            ),
        ) {
            if (selfEntry != null) {
                item(key = selfEntry.id) {
                    ActiveBarChip(
                        subagent = selfEntry,
                        now = now,
                        onClick = { onChipClick(selfEntry) },
                    )
                }
            }
            if (runningSubagents.size > CONDENSE_THRESHOLD) {
                item(key = "__condensed__") {
                    CondensedSubagentChip(count = runningSubagents.size)
                }
            } else {
                items(items = runningSubagents, key = { it.id }) { subagent ->
                    ActiveBarChip(
                        subagent = subagent,
                        now = now,
                        onClick = { onChipClick(subagent) },
                        onViewConversation = { onViewConversation(subagent) },
                    )
                }
            }
            // letta-mobile-pvrrm: long-running background-task chips always
            // render individually, between the subagents and the terminals.
            items(items = runningBackgroundTasks, key = { it.id }) { task ->
                ActiveBarChip(
                    subagent = task,
                    now = now,
                    onClick = { onChipClick(task) },
                    onViewConversation = { onViewConversation(task) },
                )
            }
            // Lingering terminal chips trail the running set so the eye lands
            // on still-working agents first, then on freshly-finished ones.
            // letta-mobile-xrth2: trailing them keeps a freshly-finished chip
            // visually SEPARATE from a NEW running chip above, so the running
            // one never looks "done".
            items(items = terminalEntries, key = { it.id }) { subagent ->
                ActiveBarChip(
                    subagent = subagent,
                    now = now,
                    onClick = { onChipClick(subagent) },
                    onViewConversation = { onViewConversation(subagent) },
                )
            }
        }
    }
}

private const val CONDENSE_THRESHOLD = 2

/**
 * letta-mobile-dvobc / xrth2 / pvrrm: the UNIFIED active-bar chip. One chip
 * type renders subagents, the self plan, AND background tool tasks — running
 * OR terminal — differentiated only by glyph + label per [ActiveSubagent.Kind]
 * and lifecycle. Replaces the former separate Subagent/Terminal/Self chips so
 * sizing, padding, icon size and baseline are HOMOGENEOUS across the bar
 * (xrth2 layout fix).
 *
 * - Leading visual: a determinate [ProgressRing] around the kind glyph. The
 *   ring fill = TodoWrite completion fraction; the ring color encodes state
 *   (green running, yellow stuck, red failed, green/check on success) —
 *   [ActiveSubagent.ringState]/[ActiveSubagent.ringFraction] (dvobc).
 * - Label: [ActiveSubagent.statusLabel] · description, so a running/dispatched
 *   entry NEVER reads as "completed" (xrth2 copy fix).
 * - Container: success/failed terminals get the secondary/error container so
 *   the outcome is reviewable while it lingers; running chips use the neutral
 *   surface-variant (self uses the tertiary tint).
 */
@Composable
private fun ActiveBarChip(
    subagent: ActiveSubagent,
    now: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onViewConversation: () -> Unit = {},
) {
    val palette = subagent.chipPalette()
    val ringState = subagent.ringState(now)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(LettaSpacing.BUBBLE_RADIUS))
            .clickable(onClick = onClick)
            .background(palette.container)
            // letta-mobile-xrth2: homogeneous min-height + SYMMETRIC vertical
            // padding so the chip is not bottom-heavy and matches other tool
            // chips.
            .heightIn(min = LettaSpacing.CHIP_MIN_HEIGHT)
            .padding(
                horizontal = LettaSpacing.CHIP_PADDING_HORIZONTAL,
                vertical = LettaSpacing.CHIP_PADDING_VERTICAL,
            )
            .semantics {
                contentDescription = subagent.chipSemanticLabel()
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpacing.SM),
    ) {
        ProgressRing(
            fraction = subagent.ringFraction,
            determinate = subagent.hasDeterminateProgress,
            ringState = ringState,
            glyph = subagent.kindGlyph(),
            tint = palette.onContainer,
        )
        Text(
            text = subagent.chipText(),
            style = MaterialTheme.typography.labelLarge,
            color = palette.onContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subagent.canViewConversation) {
            ViewConversationAction(
                description = subagent.description,
                tint = palette.onContainer,
                onClick = onViewConversation,
            )
        }
    }
}

/** Container + on-container tint pair for a chip, by kind + lifecycle. */
private data class ChipPalette(val container: Color, val onContainer: Color)

@Composable
private fun ActiveSubagent.chipPalette(): ChipPalette = when {
    status == ActiveSubagent.Status.FAILED -> ChipPalette(
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )
    status == ActiveSubagent.Status.COMPLETED -> ChipPalette(
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )
    kind == ActiveSubagent.Kind.SELF -> ChipPalette(
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
        MaterialTheme.colorScheme.onTertiaryContainer,
    )
    else -> ChipPalette(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** letta-mobile-pvrrm: the glyph that differentiates the chip kind. */
private fun ActiveSubagent.kindGlyph(): ImageVector = when (kind) {
    // A tool task is NOT an agent — distinct glyph (a wrench) + label.
    ActiveSubagent.Kind.BACKGROUND_TASK -> LettaIcons.Tool
    ActiveSubagent.Kind.SELF -> LettaIcons.Agent
    ActiveSubagent.Kind.SUBAGENT -> LettaIcons.Agent
}

/** letta-mobile-xrth2: the visible chip text — "<state> · <description>". */
private fun ActiveSubagent.chipText(): String {
    val desc = description.ifBlank {
        if (kind == ActiveSubagent.Kind.SELF) "Your plan" else statusLabel
    }
    // Self chip already carries its progress in the description; avoid a
    // redundant "Your plan · Your plan · …".
    return if (kind == ActiveSubagent.Kind.SELF) desc else desc
}

/** Unambiguous accessibility label — never "completed" for a running entry. */
private fun ActiveSubagent.chipSemanticLabel(): String =
    "${statusLabel}: ${description.ifBlank { "(no description)" }}"

/**
 * letta-mobile-vo9y1: trailing "view conversation" affordance on a chip. An
 * external-link glyph that, when tapped, jumps to the subagent's transcript.
 * Has its own click target + semantics so it is reachable independently of
 * the chip's tap-to-todolist action.
 */
@Composable
private fun ViewConversationAction(
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = LettaIcons.ExternalLink,
        contentDescription = "View conversation: $description",
        modifier = Modifier
            .clip(RoundedCornerShape(LettaSpacing.SM))
            .clickable(onClick = onClick)
            .padding(LettaSpacing.XXXS)
            .size(LettaIconSizing.Inline),
        tint = tint,
    )
}

/** Condensed summary chip shown when many subagents are active. */
@Composable
private fun CondensedSubagentChip(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(LettaSpacing.BUBBLE_RADIUS))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .heightIn(min = LettaSpacing.CHIP_MIN_HEIGHT)
            .padding(
                horizontal = LettaSpacing.CHIP_PADDING_HORIZONTAL,
                vertical = LettaSpacing.CHIP_PADDING_VERTICAL,
            )
            .semantics {
                contentDescription = "$count subagents running"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpacing.SM),
    ) {
        // Condensed summary has no single fraction — render an indeterminate
        // ring around the agent glyph (still reduced-motion aware).
        ProgressRing(
            fraction = 0f,
            determinate = false,
            ringState = RingState.RUNNING,
            glyph = LettaIcons.Agent,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
 * letta-mobile-dvobc: DETERMINATE progress ring drawn around a chip glyph.
 * REPLACES the former indeterminate spinner.
 *
 *  - When [determinate], a clockwise arc fills to [fraction] (0..1) — the
 *    TodoWrite completion fraction. The arc starts at 12 o'clock and sweeps
 *    clockwise. The icon stays CENTERED inside the ring.
 *  - The ring COLOR encodes [ringState]: green running, yellow stuck, red
 *    failed, green on success (see [ringColor]).
 *  - Reduced-motion: the ring is determinate (a static arc), so there is no
 *    spin to suppress. When NOT determinate (no todos yet) and motion is
 *    allowed, the ring sweeps slowly as an indeterminate activity cue; under
 *    reduced-motion it falls back to a STATIC faint track ring (no animation).
 *    Rotation, when used, is applied in the DRAW phase via `graphicsLayer`
 *    (no composition/layout churn — preserves the rmzmo perf work).
 */
@Composable
private fun ProgressRing(
    fraction: Float,
    determinate: Boolean,
    ringState: RingState,
    glyph: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val color = ringColor(ringState)
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val stroke = with(androidx.compose.ui.platform.LocalDensity.current) {
        LettaSpacing.CHIP_RING_STROKE.toPx()
    }
    val safeFraction = fraction.coerceIn(0f, 1f)

    // Indeterminate sweep angle (only used when !determinate && !reducedMotion).
    val sweepRotation = if (!determinate && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "ringSweep")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ringSweepAngle",
        )
        angle
    } else {
        0f
    }

    Box(
        modifier = modifier.size(LettaSpacing.CHIP_RING_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(LettaSpacing.CHIP_RING_SIZE)
                .graphicsLayer { rotationZ = sweepRotation },
        ) {
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                size.width - stroke,
                size.height - stroke,
            )
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            // Full faint track underneath.
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Foreground arc: determinate fill (clockwise from 12 o'clock) or
            // a fixed 90° "comet" for the indeterminate (rotated) case.
            val sweep = if (determinate) 360f * safeFraction else 90f
            if (sweep > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f, // 12 o'clock
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Icon(
            imageVector = glyph,
            contentDescription = null,
            modifier = Modifier.size(LettaIconSizing.Inline),
            tint = tint,
        )
    }
}

/**
 * letta-mobile-dvobc: map a [RingState] to its design-system tint.
 *  - RUNNING / SUCCESS -> success green
 *  - STUCK -> warning yellow
 *  - ERROR -> error red
 */
@Composable
private fun ringColor(state: RingState): Color = when (state) {
    RingState.RUNNING -> MaterialTheme.customColors.successColor
        .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary
    RingState.SUCCESS -> MaterialTheme.customColors.successColor
        .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary
    RingState.STUCK -> MaterialTheme.customColors.warningTextColor
        .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.tertiary
    RingState.ERROR -> MaterialTheme.customColors.errorTextColor
        .takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.error
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

@Preview(name = "Lingering terminals + view conversation")
@Composable
private fun ActiveSubagentBarTerminalPreview() {
    LettaChatTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            ActiveSubagentBar(
                subagents = kotlinx.collections.immutable.persistentListOf(
                    ActiveSubagent(
                        id = "running_1",
                        description = "Refactoring the chat reducer",
                        subagentType = "general",
                        status = ActiveSubagent.Status.RUNNING,
                        subagentAgentId = "agent-local-running",
                    ),
                    ActiveSubagent(
                        id = "done_1",
                        description = "Audited accessibility semantics",
                        subagentType = "reviewer",
                        status = ActiveSubagent.Status.COMPLETED,
                        subagentAgentId = "agent-local-done",
                        terminalAt = 0L,
                    ),
                    ActiveSubagent(
                        id = "failed_1",
                        description = "Drafting the PR description",
                        subagentType = "writer",
                        status = ActiveSubagent.Status.FAILED,
                        terminalAt = 0L,
                    ),
                ),
            )
        }
    }
}

@Preview(name = "Rings: running / stuck / background-task")
@Composable
private fun ActiveSubagentBarRingsPreview() {
    LettaChatTheme {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            ActiveSubagentBar(
                now = 100_000L,
                subagents = kotlinx.collections.immutable.persistentListOf(
                    // Running + progressing (green ring, 2/4 filled).
                    ActiveSubagent(
                        id = "run_1",
                        description = "Refactoring the reducer",
                        subagentType = "general",
                        status = ActiveSubagent.Status.RUNNING,
                        progress = SubagentTodoProgress(completed = 2, total = 4),
                        lastUpdateAt = 100_000L,
                    ),
                    // Running but STUCK (yellow ring) — last update long ago.
                    ActiveSubagent(
                        id = "stuck_1",
                        description = "Waiting on a slow fetch",
                        subagentType = "general",
                        status = ActiveSubagent.Status.RUNNING,
                        progress = SubagentTodoProgress(completed = 1, total = 5),
                        lastUpdateAt = 0L,
                    ),
                    // letta-mobile-pvrrm: a long-running BACKGROUND TOOL task.
                    ActiveSubagent(
                        id = "bg_1",
                        description = "Building APK…",
                        subagentType = "tool",
                        status = ActiveSubagent.Status.RUNNING,
                        kind = ActiveSubagent.Kind.BACKGROUND_TASK,
                        lastUpdateAt = 100_000L,
                    ),
                ),
            )
        }
    }
}
