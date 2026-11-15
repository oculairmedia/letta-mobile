package com.letta.mobile.ui.components.foundation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.icons.LettaIcons

/**
 * The one "previous" step of a pager (a calendar's previous week, a carousel's previous page): a
 * [LettaIconButton] with the left chevron. Replaces hand-placed `Icon(Lucide.ChevronLeft)` buttons, so
 * paging reads the same everywhere and the chevron stays with the shared components.
 *
 * [contentDescription] names the step ("Previous week"). Pair it with [LettaNextButton].
 */
@Composable
fun LettaPreviousButton(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    LettaIconButton(LettaButtonIcon(LettaIcons.ChevronLeft, contentDescription), onClick = onClick, modifier = modifier, enabled = enabled)
}

/** The "next" step of a pager: [LettaPreviousButton]'s partner, with the right chevron. */
@Composable
fun LettaNextButton(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    LettaIconButton(LettaButtonIcon(LettaIcons.ChevronRight, contentDescription), onClick = onClick, modifier = modifier, enabled = enabled)
}
