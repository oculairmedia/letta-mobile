@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.recents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.requestFocus
import com.letta.mobile.ui.components.LettaListRow
import com.letta.mobile.ui.components.LettaListRowSpec
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.LettaListRowDefaults
import com.letta.mobile.ui.components.list.RosterList
import com.letta.mobile.ui.components.list.RosterSection
import com.letta.mobile.ui.components.list.RosterSpec
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.shell.sidebar.ShellConversationListUi
import com.letta.mobile.ui.shell.sidebar.ShellConversationRow
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowActions
import com.letta.mobile.ui.shell.sidebar.ShellConversationRowModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RecentRowHoverTest {
    @Test
    fun focusIsVisibleAndDisabledRowsHaveNoHoverSurface() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ProvideLettaHost(LettaHost.Pointer) {
                    Column(Modifier.width(400.dp)) {
                        LettaListRow(LettaListRowSpec("Focusable"), {}, Modifier.testTag(FOCUS_TAG))
                        LettaListRow(LettaListRowSpec("Disabled", enabled = false), {}, Modifier.testTag(DISABLED_TAG))
                    }
                }
            }
        }
        val focusable = onNodeWithTag(FOCUS_TAG)
        val bounds = focusable.fetchSemanticsNode().boundsInRoot
        focusable.requestFocus()
        waitForIdle()
        assertEquals(lightColorScheme().surfaceContainer, focusable.captureToImage().toPixelMap()[SAMPLE_X, SAMPLE_Y])
        assertEquals(bounds, focusable.fetchSemanticsNode().boundsInRoot)
        val disabled = onNodeWithTag(DISABLED_TAG).assertIsNotEnabled()
        val before = disabled.captureToImage().toPixelMap()[SAMPLE_X, SAMPLE_Y]
        disabled.performMouseInput { enter(center) }
        waitForIdle()
        assertEquals(before, disabled.captureToImage().toPixelMap()[SAMPLE_X, SAMPLE_Y])
    }

    @Test
    fun recentsAndRosterHoverWithoutMoving() = runComposeUiTest {
        setContent { HoverFixture() }
        val rows = listOf(
            onNodeWithTag(recentRowTag(IDLE_KEY)) to false,
            onNodeWithTag(recentRowTag(OPEN_KEY)) to true,
            onNodeWithText("Roster") to false,
            onNodeWithText("Roster open") to true,
        )
        for ((node, isSelected) in rows) {
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val before = node.captureToImage().toPixelMap()[SAMPLE_X, SAMPLE_Y]
            if (isSelected) assertEquals(lightColorScheme().surfaceContainerHigh, before)
            node.performMouseInput { enter(center) }
            waitForIdle()
            val after = node.captureToImage().toPixelMap()[SAMPLE_X, SAMPLE_Y]
            val expected = if (isSelected) lightColorScheme().surfaceContainerHighest else lightColorScheme().surfaceContainer
            assertEquals(expected, after, "Hover surface; selected=$isSelected; before=$before")
            assertNotEquals(before, after, "Hover must change the rendered row surface")
            assertEquals(bounds, node.fetchSemanticsNode().boundsInRoot)
            node.performMouseInput { exit() }
        }
        onAllNodesWithTag(LettaListRowDefaults.TitleTestTag, useUnmergedTree = true).assertCountEquals(4)
    }
}

@Composable
private fun HoverFixture() {
    val idle = ShellConversationRowModel(IDLE_KEY, "Idle", "", "now")
    val selected = idle.copy(id = OPEN_KEY, title = "Open", selected = true)
    MaterialTheme {
        ProvideLettaHost(LettaHost.Pointer) {
            Column(Modifier.width(400.dp)) {
                RecentInteractionsList(
                    RecentInteractionsContent(null, "Agent", listOf(idle, selected)),
                    RecentInteractionsActions({}, {}), 300.dp,
                )
                RosterList(
                    RosterSpec(
                        listOf(RosterSection("roster", listOf(idle.copy(title = "Roster"), selected.copy(title = "Roster open")))),
                        ShellConversationRowModel::id,
                    ),
                    modifier = Modifier.height(200.dp),
                ) { model, row ->
                    ShellConversationRow(model, ShellConversationRowActions({}, {}), row, ShellConversationListUi())
                }
            }
        }
    }
}

private const val IDLE_KEY = "idle"
private const val OPEN_KEY = "open"
private const val FOCUS_TAG = "focus-row"
private const val DISABLED_TAG = "disabled-row"
private const val SAMPLE_X = 200
private const val SAMPLE_Y = 2
