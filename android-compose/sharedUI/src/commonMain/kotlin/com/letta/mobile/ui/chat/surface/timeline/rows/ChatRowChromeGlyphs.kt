package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.ArrowGlyph
import com.letta.mobile.ui.components.PagerBar
import com.letta.mobile.ui.components.StatusGlyph
import com.letta.mobile.ui.components.line.ArrowDirection
import com.letta.mobile.ui.components.line.PagerStep
import com.letta.mobile.ui.components.line.StatusTone

/*
 * The timeline's chrome (its scroll-to-latest buttons, a deferred body's pager, the failure panel)
 * draws the shared glyphs too. The timeline package reaches ui.components only through its rows
 * (a direct import would close an import cycle), so these hand the shared components straight on.
 */

/** The shared [ArrowGlyph], for the timeline's chrome. */
@Composable
internal fun TimelineArrow(direction: ArrowDirection, contentDescription: String?, modifier: Modifier = Modifier, size: Dp? = null) {
    ArrowGlyph(direction, contentDescription, modifier, size)
}

/** The shared [StatusGlyph], for the timeline's chrome. */
@Composable
internal fun TimelineStatusGlyph(tone: StatusTone, size: Dp, modifier: Modifier = Modifier) {
    StatusGlyph(tone, modifier, size = size)
}

/** The shared [PagerBar], for the timeline's chrome. */
@Composable
internal fun TimelinePager(label: String, back: PagerStep, forward: PagerStep, modifier: Modifier = Modifier) {
    PagerBar(label, back, forward, modifier)
}
