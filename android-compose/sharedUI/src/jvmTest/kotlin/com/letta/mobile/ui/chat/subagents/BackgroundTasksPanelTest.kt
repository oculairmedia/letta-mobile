@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.SubagentEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.3.14: the shared Background tasks panel and its toggle (formerly desktop-only). */
class BackgroundTasksPanelTest {
    private val now = 1_791_642_000_000L

    private fun entry(id: String, status: String) = SubagentEntry(
        toolCallId = id,
        description = "work $id",
        subagentType = "General-purpose",
        status = status,
        startedAt = (now - 30_000L).toString(),
    )

    @Test
    fun thePanelListsRunningWorkAndClearsTheFinished() = runComposeUiTest {
        val subagents = listOf(entry("toolu_run", "running"), entry("toolu_done", "success"))
        setContent { MaterialTheme { BackgroundTasksPanel(subagents = subagents, now = { now }) } }
        onNodeWithTag(BackgroundTasksTags.PANEL).assertExists()
        onNodeWithTag(SubagentRosterTag.RUNNING.of("toolu_run")).assertExists()
        onNodeWithTag(SubagentRosterTag.ENDED.of("toolu_done")).assertExists()
        onNodeWithText("Clear").performClick()
        onNodeWithTag(SubagentRosterTag.ENDED.of("toolu_done")).assertDoesNotExist()
        onNodeWithTag(SubagentRosterTag.RUNNING.of("toolu_run")).assertExists()
    }

    @Test
    fun anEmptyPanelSaysSo() = runComposeUiTest {
        setContent { MaterialTheme { BackgroundTasksPanel(subagents = emptyList(), now = { now }) } }
        onNodeWithTag(SUBAGENT_ROSTER_EMPTY_TAG).assertExists()
    }

    @Test
    fun theToggleNamesTheRunningCountAndOpensThePanel() = runComposeUiTest {
        var opened = 0
        setContent { MaterialTheme { BackgroundTasksToggle(runningCount = 2, onClick = { opened++ }, inBar = true) } }
        onNodeWithTag(BackgroundTasksTags.TOGGLE).assertContentDescriptionEquals("Background tasks · 2 running")
        onNodeWithTag(BackgroundTasksTags.TOGGLE).performClick()
        runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun theLabelDropsTheCountWhenNothingRuns() {
        assertEquals("Background tasks", backgroundTasksLabel(0))
        assertEquals("Background tasks · 3 running", backgroundTasksLabel(3))
    }
}
