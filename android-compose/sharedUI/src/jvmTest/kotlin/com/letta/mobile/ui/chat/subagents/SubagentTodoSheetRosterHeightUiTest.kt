@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.ui.host.InspectorFrame
import com.letta.mobile.ui.host.InspectorTags
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.PaneContainer
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The todo sheet's roster takes all the height the sheet has left under the todos, instead of
 * stopping at a fixed box with the rest of the sheet empty; it scrolls only past that height.
 */
class SubagentTodoSheetRosterHeightUiTest {
    private val now = 1_791_642_000_000L

    private fun entry(id: String, status: String) = SubagentEntry(
        toolCallId = id,
        description = "work $id",
        subagentType = "General-purpose",
        status = status,
        parentAgentId = "agent-parent",
        parentConversationId = "conv-parent",
        startedAt = (now - 60_000L).toString(),
    )

    private val entries = List(RUNNING) { entry("run-$it", "running") } + List(FINISHED) { entry("done-$it", "success") }

    @Test
    fun aTallDockedSheetGivesTheRosterAllTheRoomUnderTheTodos() = runDesktopComposeUiTest(width = 1400, height = 1500) {
        show(TALL_FRAME_WIDTH, TALL_FRAME_HEIGHT, LettaHost.Pointer, SubagentTodoSheetState.Empty)
        val pane = onNodeWithTag(InspectorTags.DOCKED).getUnclippedBoundsInRoot()
        val roster = onNodeWithTag(SubagentTodoSheetTags.ROSTER).getUnclippedBoundsInRoot()
        // The roster reaches the pane's bottom, less the sheet's bottom padding.
        assertNear(pane.bottom - LettaDimens.Space.xxl, roster.bottom)
        assertTrue(finishedRowsComposed() > 1, "more than one finished row is shown")
        onNodeWithText("No todos yet").assertIsDisplayed()
    }

    @Test
    fun aPhoneSheetKeepsItsTodosAndScrollsTheRoster() = runDesktopComposeUiTest(width = 400, height = 900) {
        val todos = SubagentTodoSheetState.Content(
            items = listOf(
                SubagentTodoItem("Read the spec", SubagentTodoStatus.COMPLETED),
                SubagentTodoItem("Write the fix", SubagentTodoStatus.IN_PROGRESS),
            ),
            progress = SubagentTodoProgress(completed = 1, total = 2),
        )
        show(PHONE_WIDTH, PHONE_HEIGHT, phoneSheetHost(), todos)
        onNodeWithText("Write the fix").assertIsDisplayed()
        val sheet = onNodeWithTag(PHONE_SHEET).getUnclippedBoundsInRoot()
        val roster = onNodeWithTag(SubagentTodoSheetTags.ROSTER).getUnclippedBoundsInRoot()
        assertTrue(roster.bottom <= sheet.bottom - LettaDimens.Space.xxl + TOLERANCE, "roster $roster inside sheet $sheet")
        assertTrue(roster.bottom - roster.top > 0.dp, "the roster has room under the todos")
        assertTrue(finishedRowsComposed() < FINISHED, "the roster scrolls rather than growing past the sheet")
    }

    private fun ComposeUiTest.show(width: Dp, height: Dp, host: LettaHost, state: SubagentTodoSheetState) {
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                MaterialTheme {
                    ProvideLettaHost(host) {
                        InspectorFrame(Modifier.size(width, height)) {
                            Box(Modifier)
                            Sheet(state)
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    @Composable
    private fun Sheet(state: SubagentTodoSheetState) {
        SubagentTodoSheet(
            description = "Fix the sheet",
            state = state,
            onDismiss = {},
            roster = { SubagentRoster(entries = entries, onOpenConversation = null, now = { now }) },
        )
    }

    private fun ComposeUiTest.finishedRowsComposed(): Int =
        (0 until FINISHED).count { onAllNodesWithTag(SubagentRosterTag.ENDED.of("done-$it")).fetchSemanticsNodes().isNotEmpty() }

    /** A phone's bottom sheet: as tall as its content, never taller than the window. */
    private fun phoneSheetHost(): LettaHost {
        val sheet: PaneContainer = { _, content ->
            Column(Modifier.fillMaxWidth().heightIn(max = PHONE_HEIGHT).testTag(PHONE_SHEET)) { content() }
        }
        return LettaHost.Touch.copy(sheet = sheet)
    }

    private fun assertNear(expected: Dp, actual: Dp) {
        assertTrue(abs(expected.value - actual.value) <= TOLERANCE.value, "expected $expected, actual $actual")
    }

    private companion object {
        const val RUNNING = 2
        const val FINISHED = 30
        const val PHONE_SHEET = "phone-sheet"
        val TALL_FRAME_WIDTH = 1_200.dp
        val TALL_FRAME_HEIGHT = 1_400.dp
        val PHONE_WIDTH = 360.dp
        val PHONE_HEIGHT = 800.dp
        val TOLERANCE = 1.dp
    }
}
