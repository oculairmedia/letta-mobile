package com.letta.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * The one way a disclosure is clicked: a button (to assistive technology) whose click label says what
 * it will do ("Expand", "Show full report") and whose state says whether it is open, by pointer,
 * touch or keyboard. Enter or Space flips it, Escape closes it, and the arrow keys move to the line
 * above or below. Every flip plays the segment tick. [stateDescription] overrides the spoken state
 * when the line says something more specific ("Working").
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
        .clickable(role = Role.Button, onClickLabel = action, onClick = flip)
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
 * A disclosure with no words of its own, only the chevron (a long prompt's "show all"): the shared
 * [disclosureToggle] on a round target the size of a dense control, the chevron centred in it.
 */
@Composable
internal fun DisclosureButton(disclosure: LineDisclosure, modifier: Modifier = Modifier) {
    val action = stringResource(if (disclosure.expanded) disclosure.labels.collapse else disclosure.labels.expand)
    Box(
        modifier = modifier
            .sizeIn(minWidth = LettaDimens.Space.xxl, minHeight = LettaDimens.Space.xxl)
            .clip(CircleShape)
            .disclosureToggle(disclosure),
        contentAlignment = Alignment.Center,
    ) {
        DisclosureChevron(expanded = disclosure.expanded, contentDescription = action)
    }
}
