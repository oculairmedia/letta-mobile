@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page header's placement contract (letta-mobile-16b2u) at the owner's four widths: the title
 * leads its row; refresh then the labelled action close it; context sits beside the title on a
 * wide page and under it on a phone, never above; controls, caption and the page's own content
 * follow in that order; and nothing leaves the page's width.
 */
class LettaPageHeaderTest {
    private val clicks = mutableListOf<String>()

    private fun ComposeUiTest.show(width: Int, host: LettaHost, title: String? = "Schedules") {
        setContent {
            MaterialTheme {
                ProvideLettaHost(host) {
                    Box(Modifier.width(width.dp)) {
                        LettaPageHeader(
                            title = title,
                            primaryAction = LettaMenuItem(label = "New schedule", onClick = { clicks += "new" }),
                            caption = "Times in UTC-5",
                            context = { Text("PM-letta-mobile", Modifier.testTag(CONTEXT)) },
                            refresh = { LettaRefreshAction(refreshing = false, onRefresh = { clicks += "refresh" }, modifier = Modifier.testTag(REFRESH)) },
                            controls = { Text("Agenda", Modifier.testTag(CONTROLS)) },
                        ) { Text("stats", Modifier.testTag(CONTENT)) }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag).getBoundsInRoot()

    @Test
    fun onAPointerHostRefreshThenTheLabelledActionCloseTheTitleRowAtEveryWideWidth() {
        listOf(760, 1200).forEach { width ->
            runDesktopComposeUiTest(width = width, height = HEIGHT) {
                show(width, LettaHost.Pointer)
                val title = bounds(LETTA_PAGE_TITLE_TAG)
                val context = bounds(CONTEXT)
                val refresh = bounds(REFRESH)
                val action = bounds(ACTION)
                assertTrue(title.left < context.left && context.right <= refresh.left && refresh.right <= action.left, "title, context, refresh, action at $width: $title $context $refresh $action")
                assertTrue(context.top < title.bottom && context.bottom > title.top, "context shares the title's row at $width")
                assertEquals(width.dp - GUTTER_WIDE, action.right, "the action ends on the page gutter at $width")
                assertEquals(GUTTER_WIDE, title.left)
                assertRowsFollowInOrder(title.bottom)
            }
        }
    }

    @Test
    fun belowTheBreakpointContextDropsUnderTheTitleAndTouchHasNoRefreshButton() {
        listOf(360, 700).forEach { width ->
            runDesktopComposeUiTest(width = width, height = HEIGHT) {
                show(width, LettaHost.Touch)
                val title = bounds(LETTA_PAGE_TITLE_TAG)
                val action = bounds(ACTION)
                val context = bounds(CONTEXT)
                onAllNodesWithContentDescription("Refresh").assertCountEquals(0)
                assertEquals(GUTTER_COMPACT, title.left)
                assertTrue(title.right <= action.left, "the title never runs under the action at $width")
                assertEquals(width.dp - GUTTER_COMPACT, action.right, "the action stays inside the page at $width")
                assertTrue(context.top >= title.bottom && context.top >= action.bottom, "context is under the title row at $width")
                assertRowsFollowInOrder(context.bottom)
            }
        }
    }

    private fun ComposeUiTest.assertRowsFollowInOrder(above: androidx.compose.ui.unit.Dp) {
        val controls = bounds(CONTROLS)
        val caption = bounds(CAPTION)
        val content = bounds(CONTENT)
        assertTrue(controls.top >= above, "controls sit under the title and context")
        assertTrue(caption.top >= controls.bottom, "the caption sits under the controls")
        assertTrue(content.top >= caption.bottom, "the page's own content comes last")
    }

    @Test
    fun aHostTitledPageKeepsItsActionsAndTheyReportTheirClicks() = runDesktopComposeUiTest(width = 1200, height = HEIGHT) {
        show(1200, LettaHost.Pointer, title = null)
        onNodeWithTag(LETTA_PAGE_TITLE_TAG).assertDoesNotExist()
        onNodeWithTag(ACTION).performClick()
        onNodeWithContentDescription("Refresh").performClick()
        assertEquals(listOf("new", "refresh"), clicks)
    }

    private companion object {
        const val ACTION = LETTA_PAGE_PRIMARY_ACTION_TAG
        const val REFRESH = "refresh"
        const val CAPTION = LETTA_PAGE_CAPTION_TAG
        const val CONTEXT = "context"
        const val CONTROLS = "controls"
        const val CONTENT = "content"
        const val HEIGHT = 400
        val GUTTER_WIDE = 24.dp
        val GUTTER_COMPACT = 16.dp
    }
}
