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
        // letta-mobile-29h9u: terminal (lingering) chips always render
        // individually in their success/failed style — only the RUNNING set
        // condenses behind the count summary.
        val runningEntries = subagentEntries.filter { it.isActive }
        val terminalEntries = subagentEntries.filter { it.isTerminal }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LettaSpacing.chipGap),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = LettaSpacing.screenHorizontal,
                vertical = LettaSpacing.xs,
            ),
        ) {
            if (selfEntry != null) {
                item(key = selfEntry.id) {
                    SelfChip(
                        subagent = selfEntry,
                        onClick = { onChipClick(selfEntry) },
                    )
                }
            }
            if (runningEntries.size > CONDENSE_THRESHOLD) {
                item(key = "__condensed__") {
                    CondensedSubagentChip(count = runningEntries.size)
                }
            } else {
                items(items = runningEntries, key = { it.id }) { subagent ->
                    SubagentChip(
                        subagent = subagent,
                        onClick = { onChipClick(subagent) },
                        onViewConversation = { onViewConversation(subagent) },
                    )
                }
            }
            // Lingering terminal chips trail the running set so the eye lands
            // on still-working agents first, then on freshly-finished ones.
            items(items = terminalEntries, key = { it.id }) { subagent ->
                TerminalSubagentChip(
                    subagent = subagent,
                    onClick = { onChipClick(subagent) },
                    onViewConversation = { onViewConversation(subagent) },
                )
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
    onViewConversation: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(LettaSpacing.bubbleRadius))
            .clickable(onClick = onClick)
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
        if (subagent.canViewConversation) {
            ViewConversationAction(
                description = subagent.description,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onViewConversation,
            )
        }
    }
}

/**
 * letta-mobile-29h9u: a recently-terminal subagent chip. Renders the OUTCOME
 * — a green check for [ActiveSubagent.Status.COMPLETED], a red error for
 * [ActiveSubagent.Status.FAILED] — using design-system tokens so the user can
 * review success/failure before the chip dismisses (it lingers per
 * [ActiveSubagent.TERMINAL_LINGER_MS]). No spinner, so reduced-motion is a
 * no-op here. Still tappable (opens the todo sheet) and offers the
 * view-conversation affordance when correlated.
 */
@Composable
private fun TerminalSubagentChip(
    subagent: ActiveSubagent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onViewConversation: () -> Unit = {},
) {
    val failed = subagent.status == ActiveSubagent.Status.FAILED
    val container = if (failed) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = if (failed) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val icon = if (failed) LettaIcons.Error else LettaIcons.CheckCircle
    val outcomeLabel = if (failed) "Failed subagent" else "Completed subagent"
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(LettaSpacing.bubbleRadius))
            .clickable(onClick = onClick)
            .background(container)
            .padding(horizontal = LettaSpacing.md, vertical = LettaSpacing.xs)
            .semantics {
                contentDescription = "$outcomeLabel: ${subagent.description}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(LettaIconSizing.Inline),
            tint = onContainer,
        )
        Text(
            text = subagent.description,
            style = MaterialTheme.typography.labelLarge,
            color = onContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subagent.canViewConversation) {
            ViewConversationAction(
                description = subagent.description,
                tint = onContainer,
                onClick = onViewConversation,
            )
        }
    }
}

/**
 * letta-mobile-vo9y1: trailing "view conversation" affordance on a chip. An
 * external-link glyph that, when tapped, jumps to the subagent's transcript.
 * Has its own click target + semantics so it is reachable independently of
 * the chip's tap-to-todolist action.
 */
@Composable
private fun ViewConversationAction(
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = LettaIcons.ExternalLink,
        contentDescription = "View conversation: $description",
        modifier = Modifier
            .clip(RoundedCornerShape(LettaSpacing.sm))
            .clickable(onClick = onClick)
            .padding(LettaSpacing.xxxs)
            .size(LettaIconSizing.Inline),
        tint = tint,
    )
}

/**
 * letta-mobile-gnyf7: the synthetic "self" chip — the MAIN/foreground agent's
 * OWN TodoWrite plan. Visually differentiated from subagent chips (tertiary
 * tint + agent icon + "You" label) so it reads as the user's own agent, not a
 * dispatched worker. Taps open the same todo sheet, resolved from the
 * self-todo source.
 */
@Composable
private fun SelfChip(
    subagent: ActiveSubagent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(LettaSpacing.bubbleRadius))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f))
            .padding(horizontal = LettaSpacing.md, vertical = LettaSpacing.xs)
            .semantics {
                contentDescription = "Your agent's current plan: ${subagent.description}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpacing.sm),
    ) {
        Icon(
            imageVector = LettaIcons.Agent,
            contentDescription = null,
            modifier = Modifier.size(LettaIconSizing.Inline),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text = subagent.description.ifBlank { "Your plan" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
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
