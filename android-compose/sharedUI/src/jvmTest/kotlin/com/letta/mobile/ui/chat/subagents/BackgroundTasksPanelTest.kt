@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import kotlinx.coroutines.flow.flowOf
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
    fun runningTaskCanBeDismissedByItsExactIdentity() = runComposeUiTest {
        val scoped = entry("toolu_run", "running").copy(parentAgentId = "agent-a", parentConversationId = "conv-a")
        val calls = mutableListOf<SubagentEntry>()
        var entries by mutableStateOf(listOf(scoped))
        setContent {
            MaterialTheme {
                BackgroundTasksPanel(subagents = entries, now = { now }, onDismiss = {
                    calls += it
                    entries = entries.filterNot { entry -> entry.toolCallId == it.toolCallId }
                })
            }
        }
        onNodeWithText("Dismiss").performClick()
        runOnIdle { assertEquals(listOf(scoped), calls) }
        onNodeWithTag(SubagentRosterTag.RUNNING.of("toolu_run")).assertDoesNotExist()
    }

    @Test
    fun failedDismissalRemainsVisibleAndReportsFailure() = runComposeUiTest {
        val scope = SubagentParentScope("agent-a", "conv-a")
        val scoped = entry("toolu_run", "running").copy(parentAgentId = "agent-a", parentConversationId = "conv-a")
        val calls = mutableListOf<Pair<SubagentParentScope, String>>()
        var current by mutableStateOf(listOf(scoped))
        val repository = object : ISubagentRepository {
            override fun activeSubagentsFlow(scope: SubagentParentScope) = flowOf(listOf(scoped))
            override fun currentActiveSubagents(scope: SubagentParentScope) = listOf(scoped)
            override suspend fun refresh() = Result.success(listOf(scoped))
            override suspend fun todos(toolCallId: String) = Result.success(emptyList<SubagentTodo>())
            override suspend fun dismiss(scope: SubagentParentScope, toolCallId: String): Result<Unit> {
                calls += scope to toolCallId
                return Result.failure(IllegalStateException("full"))
            }
        }
        setContent {
            MaterialTheme {
                BackgroundTasksPanel(subagents = current, now = { now }, repository = repository, parentScope = scope)
            }
        }
        onNodeWithText("Dismiss").performClick()
        onNodeWithText("Could not dismiss task. Please try again.").assertExists()
        onNodeWithTag(SubagentRosterTag.RUNNING.of("toolu_run")).assertExists()
        runOnIdle {
            assertEquals(listOf(scope to "toolu_run"), calls)
            current = listOf(scoped.copy(parentAgentId = "other-agent"))
        }
        onNodeWithText("Dismiss").performClick()
        onNodeWithText("Could not dismiss task. Please try again.").assertExists()
        runOnIdle { assertEquals(listOf(scope to "toolu_run"), calls) }
    }

    @Test
    fun missingParentIdentityOffersNoDismissAction() = runComposeUiTest {
        setContent {
            MaterialTheme {
                BackgroundTasksPanel(subagents = listOf(entry("toolu_run", "running")), now = { now }, onDismiss = {})
            }
        }
        onNodeWithText("Dismiss").assertDoesNotExist()
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
