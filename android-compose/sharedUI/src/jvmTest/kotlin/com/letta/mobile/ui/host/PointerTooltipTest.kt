@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.shell.shellHoverTimeLabel
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared pointer tooltips: every pointer host (desktop and web) shows the same hover label and card. */
class PointerTooltipTest {
    @Test
    fun thePointerHostShowsAHoverLabelAfterTheDelayAndHidesItOnExit() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            MaterialTheme {
                ProvideLettaHost(LettaHost.Pointer) {
                    LocalLettaHost.current.tooltip("Open settings") { Box(Modifier.size(40.dp).testTag("control")) }
                }
            }
        }
        onNodeWithTag("control").performMouseInput { moveTo(Offset(10f, 10f)) }
        mainClock.advanceTimeBy(50)
        onNodeWithText("Open settings").assertDoesNotExist()
        mainClock.advanceTimeBy(200)
        onNodeWithText("Open settings").assertExists()
        onNodeWithTag("control").performMouseInput { exit() }
        mainClock.advanceTimeBy(50)
        onNodeWithText("Open settings").assertDoesNotExist()
    }

    @Test
    fun thePointerHostShowsAHoverCardWithItsTitleAndBody() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ProvideLettaHost(LettaHost.Pointer) {
                    LocalLettaHost.current.hoverCard(LettaHoverCard("Ada", "not a time", "Reviewing PR")) {
                        Box(Modifier.size(40.dp).testTag("orb"))
                    }
                }
            }
        }
        onNodeWithTag("orb").performMouseInput { moveTo(Offset(10f, 10f)) }
        mainClock.advanceTimeBy(300)
        onNodeWithText("Ada").assertExists()
        onNodeWithText("not a time").assertExists()
        onNodeWithText("Reviewing PR").assertExists()
    }

    @Test
    fun theCursorPlacementSitsBelowAfterTheCursorAndFlipsAtTheWindowEdge() {
        val provider = cursorTooltipPosition(cursor = IntOffset(5, 5), offset = IntOffset(12, 12), windowMargin = 4)
        val anchor = IntRect(IntOffset(100, 100), IntSize(40, 40))
        val popup = IntSize(80, 20)
        assertEquals(IntOffset(117, 117), provider.calculatePosition(anchor, IntSize(1000, 1000), LayoutDirection.Ltr, popup))
        assertEquals(IntOffset(37, 97), provider.calculatePosition(anchor, IntSize(150, 130), LayoutDirection.Ltr, popup))
    }

    @Test
    fun theAnchorPlacementCentresOnTheControl() {
        val provider = anchorTooltipPosition(Alignment.BottomCenter, Alignment.BottomCenter, IntOffset(0, 8))
        val anchor = IntRect(IntOffset(100, 100), IntSize(40, 40))
        val position = provider.calculatePosition(anchor, IntSize(1000, 1000), LayoutDirection.Ltr, IntSize(80, 20))
        assertEquals(IntOffset(80, 148), position)
    }

    @Test
    fun theHoverCardTimeIsAClockTimeTodayAndAnAgeBefore() {
        val now = Instant.parse("2026-10-30T18:00:00Z")
        assertEquals("8:07 AM", shellHoverTimeLabel("2026-10-30T08:07:00Z", now, TimeZone.UTC))
        assertEquals("3d", shellHoverTimeLabel("2026-10-27T17:00:00Z", now, TimeZone.UTC))
        assertEquals("Queued", shellHoverTimeLabel("Queued", now, TimeZone.UTC))
    }
}
