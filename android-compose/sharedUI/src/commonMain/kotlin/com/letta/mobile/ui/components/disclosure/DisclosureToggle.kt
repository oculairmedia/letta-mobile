package com.letta.mobile.ui.components.disclosure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.foundation.LettaClickTarget
import com.letta.mobile.ui.components.foundation.lettaClickable
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import org.jetbrains.compose.resources.stringResource

/**
 * The one way a disclosure is clicked: a button (to assistive technology) whose click label says what
 * it will do ("Expand", "Show full report") and whose state says whether it is open, by pointer,
 * touch or keyboard. Enter or Space flips it, Escape closes it, and the arrow keys move to the line
 * above or below. Every flip plays the segment tick. [stateDescription] overrides the spoken state
 * when the line says something more specific ("Working"). It is [lettaClickable] on an inline target,
 * so a line keeps the height its rail gives it.
 */
@Composable
internal fun Modifier.disclosureToggle(disclosure: LineDisclosure, stateDescription: String? = null): Modifier {
    val labels = disclosure.labels
    val spoken = stateDescription ?: stringResource(if (disclosure.expanded) labels.expandedState else labels.collapsedState)
    val action = stringResource(if (disclosure.expanded) labels.collapse else labels.expand)
    val focus = LocalFocusManager.current
    val haptics = LocalHaptics.current
    val flip = {
        haptics.play(LettaHapticCue.SegmentTick)
        disclosure.onToggle()
    }
    return onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && disclosureKey(event.key, disclosure.expanded, flip, focus) }
        .lettaClickable(role = Role.Button, onClickLabel = action, target = LettaClickTarget.Inline, onClick = flip)
        .semantics { this.stateDescription = spoken }
}

/** What a key does on a focused disclosure; false when it is not the disclosure's to handle. */
private fun disclosureKey(key: Key, expanded: Boolean, flip: () -> Unit, focus: FocusManager): Boolean = when (key) {
    Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
        flip()
        true
    }
    Key.Escape -> expanded.also { open -> if (open) flip() }
    Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
    Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
    else -> false
}

/**
 * How a [DisclosureRow] is laid out: the [gap] between its parts, its [contentPadding] (inside the
 * toggle, so the ripple covers it), and whether its chevron is the [compactChevron] size.
 */
@Immutable
internal class DisclosureRowStyle(
    val gap: Dp = LettaSpaceTokens.sm,
    val contentPadding: PaddingValues = PaddingValues(),
    val compactChevron: Boolean = false,
) {
    companion object {
        /** A header line with no inset of its own: the chevron at the standard glyph size. */
        val Default: DisclosureRowStyle = DisclosureRowStyle()
    }
}

/** What a [DisclosureRow] says beyond its disclosure's labels: its spoken state and its chevron's label. */
@Immutable
internal class DisclosureRowWords(val stateDescription: String? = null, val chevronDescription: String? = null) {
    companion object {
        val Default: DisclosureRowWords = DisclosureRowWords()
    }
}

/**
 * The one disclosure line: a full-width row, the whole of it the [disclosureToggle] when [disclosure]
 * is set, the caller's [content], and the one trailing [DisclosureChevron]. Every disclosure line
 * draws through here: the timeline's lines (on their rail's insets) and [LettaDisclosureHeader].
 * With no [disclosure] it is the same row with nothing to open and no chevron.
 */
@Composable
internal fun DisclosureRow(
    disclosure: LineDisclosure?,
    modifier: Modifier = Modifier,
    style: DisclosureRowStyle = DisclosureRowStyle.Default,
    words: DisclosureRowWords = DisclosureRowWords.Default,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (disclosure != null) Modifier.disclosureToggle(disclosure, words.stateDescription) else Modifier)
            .padding(style.contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(style.gap),
    ) {
        content()
        if (disclosure != null) {
            val action = words.chevronDescription
                ?: stringResource(if (disclosure.expanded) disclosure.labels.collapse else disclosure.labels.expand)
            DisclosureChevron(expanded = disclosure.expanded, compact = style.compactChevron, contentDescription = action)
        }
    }
}

/**
 * A disclosure with no words of its own, only the chevron (a long prompt's "show all"): the shared
 * [disclosureToggle] on a round target the size of a dense control, the chevron centred in it.
 */
@Composable
internal fun DisclosureButton(disclosure: LineDisclosure, modifier: Modifier = Modifier) {
    val action = stringResource(if (disclosure.expanded) disclosure.labels.collapse else disclosure.labels.expand)
    Box(
        modifier = modifier
            .sizeIn(minWidth = LettaSpaceTokens.xxl, minHeight = LettaSpaceTokens.xxl)
            .clip(CircleShape)
            .disclosureToggle(disclosure),
        contentAlignment = Alignment.Center,
    ) {
        DisclosureChevron(expanded = disclosure.expanded, contentDescription = action)
    }
}
