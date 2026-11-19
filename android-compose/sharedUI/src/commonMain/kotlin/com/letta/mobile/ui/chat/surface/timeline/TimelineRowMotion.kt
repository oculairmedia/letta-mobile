package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/**
 * A timeline row's placement motion (legacy ChatMessageListLazyColumn
 * `Modifier.animateItem`): a new step (thought, then tool, then prose) fades in and the rows it
 * displaces glide to their new places instead of snapping. Off under reduced motion.
 *
 * [fadesIn] is false for a row whose entrance something else owns: the prompt the person just
 * sent is revealed by the send flight's hand-off, and a second fade would dip it.
 *
 * [moves] is the list's [rememberTimelineRowMotionGate]: false while nothing new is landing, and
 * then the row has no item motion at all. The lazy list's item animator cannot tell new content
 * from a scroll. A row that enters the viewport in the same pass as an index shift (Paging drops a
 * page at one end and prepends it again at the other) slides in from the viewport's edge. A row
 * whose key left the data set and came back (a dropped page loaded again) fades in. Rows displaced
 * by a neighbour whose height lands a frame after it composes (a parse off the UI thread) glide.
 * None of that is new content, and mouse-wheel scrolling, which jumps several rows per notch, hits
 * it far more often than a fling does.
 */
@Composable
internal fun LazyItemScope.timelineRowMotion(fadesIn: Boolean = true, moves: Boolean = true): Modifier {
    if (LocalReducedMotion.current || !moves) return Modifier
    return if (fadesIn) Modifier.animateItem() else Modifier.animateItem(fadeInSpec = null)
}

/**
 * Whether the timeline's rows may move by themselves: while [newContentActive] (a turn is in
 * flight, or live rows are on the list) and for [settleMillis] after, so the turn's settle can still
 * glide. Never while the list is scrolling: a row that moves with the scroll is drawn where the
 * scroll puts it, and nothing else.
 *
 * Read the value inside each row (not by the list) so a change recomposes only the rows on screen.
 */
@Composable
internal fun rememberTimelineRowMotionGate(
    listState: LazyListState,
    newContentActive: Boolean,
    settleMillis: Long = LettaMotionTokens.ROW_MOTION_SETTLE_MILLIS.toLong(),
): State<Boolean> {
    val recent = remember(listState) { mutableStateOf(newContentActive) }
    LaunchedEffect(recent, newContentActive) {
        if (newContentActive) {
            recent.value = true
        } else {
            delay(settleMillis)
            recent.value = false
        }
    }
    return remember(listState, recent) { derivedStateOf { recent.value && !listState.isScrollInProgress } }
}

/**
 * Whether one row may move now: whenever the [gate] allows, and a [live] row also in the frame it
 * lands, before the gate has seen its turn begin, unless the list is scrolling.
 */
internal fun timelineRowMoves(gate: State<Boolean>, live: Boolean, listState: LazyListState): Boolean =
    gate.value || (live && !listState.isScrollInProgress)

