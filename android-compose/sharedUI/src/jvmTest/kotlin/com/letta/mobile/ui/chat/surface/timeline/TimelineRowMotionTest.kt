@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Rows already shown move only with the scroll. A reversed list like the timeline's, every row
 * carrying the production [timelineRowMotion] behind [rememberTimelineRowMotionGate], is driven by
 * discrete mouse-wheel notches while its data churns the way Paging's does: a page dropped at the
 * newest end while the reader is up in history (every index shifts), and loaded again on the way
 * back (its keys return). Some rows reach their height a frame after they compose, as an uncached
 * markdown parse does. Every frame, each visible row must sit flush against its neighbour and be
 * drawn at full opacity: a placement glide opens a gap, and an entrance fade dims the row.
 */
class TimelineRowMotionTest {

    @Test
    fun wheelScrollingThroughPagingChurnMovesRowsOnlyWithTheScroll() = runComposeUiTest {
        val rig = showRig(turnActive = false)
        mainClock.autoAdvance = false
        val violations = mutableListOf<String>()

        repeat(OLDER_NOTCHES) { wheel(rig, TOWARD_OLDER, violations, "toward older $it") }
        val upAt = rig.listState.firstVisibleItemIndex
        assertTrue(upAt > PAGE, "the reader is up past the newest page: $upAt")

        // Paging drops the newest page while the reader is up in history, mid-notch: every index shifts.
        wheel(rig, TOWARD_OLDER, violations, "drop newest page") { rig.window = rig.window.drop(PAGE) }

        // On the way back the dropped page loads again, mid-notch: its keys return.
        repeat(OLDER_NOTCHES) { notch ->
            val reload = notch == OLDER_NOTCHES / 2
            wheel(rig, TOWARD_NEWER, violations, "toward newer $notch") {
                if (reload) rig.window = allRows.take(PAGE) + rig.window
            }
        }

        // Discrete jumps landing in the same frame as a drop, and then as the reload.
        rig.window = rig.window.drop(PAGE)
        rig.jump(JUMP_PX)
        settle(rig, violations, "jump with drop")
        rig.window = allRows.take(PAGE) + rig.window
        rig.jump(-JUMP_PX)
        settle(rig, violations, "jump with reload")

        assertEquals(emptyList(), violations.take(MAX_REPORTED), "rows moved by themselves during a pure scroll")
    }

    @Test
    fun aLiveRowStillFadesIn() = runComposeUiTest {
        val rig = showRig(turnActive = true)
        mainClock.autoAdvance = false
        settle(rig, mutableListOf(), "opening")

        val live = Row("live", Color(0xFF2E7D32), heightDp = 120, late = false)
        rig.liveKeys = setOf(live.key)
        rig.window = listOf(live) + rig.window
        rig.toNewest()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        assertNotEquals(live.color.toArgb(), centrePixel(live.key), "a live row's first frames are part of its fade-in")
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeByFrame() }
        assertEquals(live.color.toArgb(), centrePixel(live.key), "the fade ends fully opaque")
    }

    @Test
    fun reducedMotionDrawsALiveRowAtOnce() = runComposeUiTest {
        val rig = showRig(turnActive = true, reducedMotion = true)
        mainClock.autoAdvance = false
        settle(rig, mutableListOf(), "opening")

        val live = Row("live", Color(0xFF2E7D32), heightDp = 120, late = false)
        rig.liveKeys = setOf(live.key)
        rig.window = listOf(live) + rig.window
        rig.toNewest()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        assertEquals(live.color.toArgb(), centrePixel(live.key))
    }

    @Test
    fun theGateStaysOpenThroughTheSettleAndClosesAfter() = runComposeUiTest {
        var active by mutableStateOf(true)
        lateinit var gate: State<Boolean>
        setContent {
            val listState = rememberLazyListState()
            gate = rememberTimelineRowMotionGate(listState, active)
            LazyColumn(state = listState) { items(3) { Box(Modifier.size(10.dp)) } }
        }
        mainClock.autoAdvance = false
        mainClock.advanceTimeByFrame()
        assertTrue(gate.value, "open while new content lands")

        active = false
        mainClock.advanceTimeBy(SETTLE_MILLIS / 2)
        assertTrue(gate.value, "still open while the settle glides")
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        assertFalse(gate.value, "closed once the turn has settled")
    }

    private class Rig(val listState: LazyListState) {
        var window by mutableStateOf(allRows)
        var liveKeys by mutableStateOf(emptySet<String>())

        fun jump(px: Float) {
            listState.dispatchRawDelta(px)
        }

        /** The follow's move to the newest row, applied in the same layout as the insert. */
        fun toNewest() {
            listState.requestScrollToItem(0)
        }

        fun colorOf(key: String): Color = window.first { it.key == key }.color
    }

    private fun ComposeUiTest.showRig(turnActive: Boolean, reducedMotion: Boolean = false): Rig {
        lateinit var rig: Rig
        setContent {
            val listState = rememberLazyListState()
            rig = remember { Rig(listState) }
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                MotionList(rig, turnActive)
            }
        }
        return rig
    }

    @Composable
    private fun MotionList(rig: Rig, turnActive: Boolean) {
        val gate = rememberTimelineRowMotionGate(rig.listState, turnActive)
        LazyColumn(
            state = rig.listState,
            reverseLayout = true,
            modifier = Modifier.size(width = 360.dp, height = VIEWPORT_DP.dp).background(Color.White).testTag(LIST),
        ) {
            items(rig.window, key = { it.key }) { row ->
                val live = row.key in rig.liveKeys
                val motion = timelineRowMotion(fadesIn = true, moves = timelineRowMoves(gate, live, rig.listState))
                LateRow(row, motion)
            }
        }
    }

    /** A row; a late one reaches its height a frame after it composes, every time it composes. */
    @Composable
    private fun LateRow(row: Row, modifier: Modifier) {
        var landed by remember { mutableStateOf(!row.late) }
        if (!landed) LaunchedEffect(Unit) { withFrameNanos { }; landed = true }
        val height = if (landed) row.heightDp else row.heightDp / LATE_SHRINK
        Box(modifier.fillMaxWidth().height(height.dp).background(row.color).testTag(row.key))
    }

    private fun ComposeUiTest.wheel(
        rig: Rig,
        direction: Float,
        violations: MutableList<String>,
        step: String,
        midway: () -> Unit = {},
    ) {
        onNodeWithTag(LIST).performMouseInput {
            moveTo(center)
            scroll(direction * NOTCH)
        }
        repeat(MIDWAY_FRAMES) { frame ->
            mainClock.advanceTimeByFrame()
            checkFlush(rig, "$step, frame $frame", violations)
        }
        midway()
        settle(rig, violations, step)
    }

    private fun ComposeUiTest.settle(rig: Rig, violations: MutableList<String>, step: String) {
        repeat(SETTLE_FRAMES) { frame ->
            mainClock.advanceTimeByFrame()
            checkFlush(rig, "$step, frame $frame", violations)
        }
    }

    /** Visible rows sit edge to edge (reversed: a higher index is above) at full opacity. */
    private fun ComposeUiTest.checkFlush(rig: Rig, step: String, violations: MutableList<String>) {
        val list = onNodeWithTag(LIST).getBoundsInRoot()
        val visible = rig.listState.layoutInfo.visibleItemsInfo.sortedBy { it.index }.map { it.key as String }
        val bounds = visible.associateWith { onNodeWithTag(it).getBoundsInRoot() }
        visible.zipWithNext().forEach { (newer, older) ->
            val gap = (bounds.getValue(newer).top - bounds.getValue(older).bottom).value
            if (abs(gap) > TOLERANCE_DP) violations += "$step: $older is ${gap}dp off $newer"
        }
        val pixels = onRoot().captureToImage().toPixelMap()
        visible.forEach { key ->
            val box = bounds.getValue(key).clippedTo(list) ?: return@forEach
            val expected = rig.colorOf(key)
            val drawn = pixels.centre(box, density.density)
            if (drawn.toArgb() != expected.toArgb()) violations += "$step: $key drawn $drawn, not $expected"
        }
    }

    private fun ComposeUiTest.centrePixel(key: String): Int {
        val box = checkNotNull(onNodeWithTag(key).getBoundsInRoot().clippedTo(onNodeWithTag(LIST).getBoundsInRoot()))
        return onRoot().captureToImage().toPixelMap().centre(box, density.density).toArgb()
    }

    private fun DpRect.clippedTo(clip: DpRect): DpRect? {
        val top = maxOf(top, clip.top)
        val bottom = minOf(bottom, clip.bottom)
        if ((bottom - top).value <= TOLERANCE_DP) return null
        return DpRect(left, top, right, bottom)
    }

    private fun PixelMap.centre(box: DpRect, density: Float): Color {
        val x = ((box.left + box.right).value / 2f * density).roundToInt().coerceIn(0, width - 1)
        val y = ((box.top + box.bottom).value / 2f * density).roundToInt().coerceIn(0, height - 1)
        return this[x, y]
    }

    private data class Row(val key: String, val color: Color, val heightDp: Int, val late: Boolean)

    private companion object {
        const val LIST = "motion-list"
        const val PAGE = 10
        const val ROWS = 60
        const val VIEWPORT_DP = 600
        const val OLDER_NOTCHES = 8
        const val SETTLE_FRAMES = 40
        const val MIDWAY_FRAMES = 2
        const val SETTLE_MILLIS = LettaMotionTokens.ROW_MOTION_SETTLE_MILLIS.toLong()
        const val NOTCH = 12f
        const val JUMP_PX = 420f
        const val LATE_SHRINK = 3
        const val TOLERANCE_DP = 1f
        const val MAX_REPORTED = 8

        /** Wheel deltas toward the oldest rows and back (a reversed list). */
        const val TOWARD_OLDER = -1f
        const val TOWARD_NEWER = 1f

        val allRows: List<Row> = List(ROWS) { i ->
            Row(
                key = "row-$i",
                color = Color(red = (i * 37) % 200 + 30, green = (i * 59) % 200 + 30, blue = (i * 83) % 200 + 30),
                heightDp = 60 + (i * 29) % 90,
                late = i % 3 == 1,
            )
        }

    }
}
