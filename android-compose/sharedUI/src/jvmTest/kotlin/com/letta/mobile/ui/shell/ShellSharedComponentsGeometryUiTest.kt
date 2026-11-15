@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.channel.ChannelDisplayItem
import com.letta.mobile.data.channel.ChannelDisplayStatus
import com.letta.mobile.data.channel.ChannelLibraryState
import com.letta.mobile.data.channel.ChannelsPageActions
import com.letta.mobile.data.channel.ChannelsPageReducer
import com.letta.mobile.data.channel.ChannelsPageState
import com.letta.mobile.data.schedules.SchedulesView
import com.letta.mobile.ui.components.LETTA_PAGE_TITLE_TAG
import com.letta.mobile.ui.components.foundation.LettaNextButton
import com.letta.mobile.ui.components.foundation.LettaPreviousButton
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.pages.channels.ChannelsPage
import com.letta.mobile.ui.shell.pages.channels.ChannelsPageTags
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageTags
import com.letta.mobile.ui.shell.pages.schedules.SchedulesViewBar
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Geometry of the shell surfaces moved onto shared components, at the four widths every UI change is
 * checked at (a phone, the two sides of the 720dp page breakpoint, a desktop window): the page header
 * keeps its search and filters inside the window and beside or under the title by width, the phone's
 * schedules view bar keeps every segment inside its share at full touch height, and the pager steps
 * keep the platform touch target.
 */
class ShellSharedComponentsGeometryUiTest {
    @Test
    fun theChannelsHeaderKeepsItsControlsInsideTheWindowAtEveryWidth() {
        WIDTHS.forEach { width ->
            runComposeUiTest {
                showChannels(width)
                val window = Rect(0f, 0f, width.value, PAGE_HEIGHT.value)
                val title = boundsOf(LETTA_PAGE_TITLE_TAG)
                val search = boundsOf(ChannelsPageTags.SEARCH)
                val chip = boundsOf(ChannelsPageTags.filter(ALL_CHANNELS))
                assertInside(window, title, "title at $width")
                assertInside(window, search, "search at $width")
                assertInside(window, chip, "filter chip at $width")
                if (width >= LettaDimens.Pane.wideBreakpoint) {
                    // A wide header puts the search on the title's row, after it.
                    assertTrue(search.left >= title.right, "search after the title at $width")
                    assertTrue(search.top < title.bottom && search.bottom > title.top, "search on the title row at $width")
                } else {
                    // A phone header gives the search a row of its own under the title, full width less the gutters.
                    assertTrue(search.top >= title.bottom, "search under the title at $width")
                    assertEquals(width.value - 2 * LettaDimens.Space.lg.value, search.width, 1f, "search width at $width")
                }
                assertTrue(chip.top >= search.bottom, "filters under the search at $width")
            }
        }
    }

    @Test
    fun thePhoneScheduleViewBarFitsEverySegmentAtTouchHeight() {
        WIDTHS.forEach { width ->
            runComposeUiTest {
                setContent {
                    MaterialTheme {
                        Box(Modifier.width(width)) { SchedulesViewBar(SchedulesView.entries.first()) {} }
                    }
                }
                val bar = boundsOf(SchedulesPageTags.VIEW_BAR)
                assertEquals(width.value, bar.width, 1f, "bar width at $width")
                var previousRight = Float.NEGATIVE_INFINITY
                SchedulesView.entries.forEach { view ->
                    val segment = boundsOf(SchedulesPageTags.tab(view))
                    assertInside(bar, segment, "${view.name} at $width")
                    assertTrue(segment.height >= LettaDimens.Control.minTouchTarget.value - 1f, "${view.name} touch height at $width")
                    assertTrue(segment.left >= previousRight - 1f, "${view.name} after the previous segment at $width")
                    previousRight = segment.right
                }
            }
        }
    }

    @Test
    fun thePagerStepsKeepTheTouchTarget() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(Modifier.width(PHONE)) {
                    LettaPreviousButton(onClick = {}, contentDescription = "Previous", modifier = Modifier.testTag(PREVIOUS))
                }
                Box(Modifier.width(PHONE)) {
                    LettaNextButton(onClick = {}, contentDescription = "Next", modifier = Modifier.testTag(NEXT))
                }
            }
        }
        listOf(PREVIOUS, NEXT).forEach { tag ->
            val node = onNodeWithTag(tag).fetchSemanticsNode()
            assertEquals(LettaDimens.Control.iconButton.value, node.layoutInfo.width / density.density, 1f, "$tag layout width")
            assertTrue(node.touchBoundsInRoot.width / density.density >= LettaDimens.Control.minTouchTarget.value - 1f, "$tag touch width")
        }
    }

    private fun ComposeUiTest.showChannels(width: Dp) {
        val state = ChannelsPageReducer.withLibrary(ChannelsPageState(), ChannelLibraryState(listOf(CHANNEL)))
        setContent {
            MaterialTheme {
                ProvideLettaHost(LettaHost.Pointer) {
                    Box(Modifier.width(width).height(PAGE_HEIGHT)) {
                        ChannelsPage(state = state, actions = NoActions)
                    }
                }
            }
        }
        onNodeWithText(CHANNEL.title).assertExists()
    }

    /** A node's bounds in the root, in dp. */
    private fun ComposeUiTest.boundsOf(tag: String): Rect {
        val px = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val scale = density.density
        return Rect(px.left / scale, px.top / scale, px.right / scale, px.bottom / scale)
    }

    private fun assertInside(outer: Rect, inner: Rect, what: String) {
        val slack = 1f
        assertTrue(
            inner.left >= outer.left - slack && inner.top >= outer.top - slack &&
                inner.right <= outer.right + slack && inner.bottom <= outer.bottom + slack,
            "$what: $inner is not inside $outer",
        )
    }

    private object NoActions : ChannelsPageActions {
        override fun refresh() = Unit

        override fun updateQuery(query: String) = Unit

        override fun selectStatusFilter(status: ChannelDisplayStatus?) = Unit

        override fun selectChannel(channelId: String) = Unit

        override fun clearSelection() = Unit
    }

    private companion object {
        val PHONE = 360.dp
        val WIDTHS = listOf(PHONE, 700.dp, 760.dp, 1200.dp)
        val PAGE_HEIGHT = 800.dp
        const val ALL_CHANNELS = "All channels"
        const val PREVIOUS = "pager_previous"
        const val NEXT = "pager_next"
        val CHANNEL = ChannelDisplayItem(
            id = "iroh",
            title = "Iroh backend",
            subtitle = "Connected via iroh",
            detailText = "Connected to server s1 using iroh.",
            metadataLabels = listOf("Connected", "iroh"),
            status = ChannelDisplayStatus.Connected,
        )
    }
}
