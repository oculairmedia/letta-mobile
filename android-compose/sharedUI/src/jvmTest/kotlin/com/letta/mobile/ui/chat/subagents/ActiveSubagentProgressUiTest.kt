@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reads the production composable's target sweep, also exposed as accessible progress. */
class ActiveSubagentProgressUiTest {
    private fun running(progress: SubagentTodoProgress? = null) = ActiveSubagent(
        id = "dispatch", description = "Test progress", subagentType = "general-purpose",
        status = ActiveSubagent.Status.RUNNING, progress = progress,
    )

    @Test
    fun historicalFailuresDoNotRenderAsSixFreshRings() = runComposeUiTest {
        val parent = com.letta.mobile.data.repository.api.SubagentParentScope("parent", "ring-parent")
        val live = com.letta.mobile.data.model.SubagentEntry(
            toolCallId = "live", status = "running", parentAgentId = parent.parentAgentId,
            parentConversationId = parent.parentConversationId,
        )
        val history = (1..5).map { live.copy(toolCallId = "old-$it", status = "failed") }
        val transport = object : com.letta.mobile.data.transport.api.NoOpChannelTransport() {
            override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long) =
                com.letta.mobile.data.transport.ServerFrame.SubagentListResponse(
                    id = "history", ts = "t", success = true, subagents = if (all) history + live else listOf(live),
                )
        }
        val repository = com.letta.mobile.data.repository.SubagentRepository(transport, includeAll = true, clock = { 100_000L })
        try {
            kotlinx.coroutines.runBlocking { repository.refresh().getOrThrow() }
            val snapshot = repository.currentActiveSubagents(parent).map { it.toActiveSubagent() }
            setContent {
                MaterialTheme {
                    ActiveSubagentRings(snapshot.toImmutableList(), now = 100_000L)
                }
            }
            onNodeWithTag(ActiveSubagentRingsTags.ring("live")).assertExists()
            history.forEach { onNodeWithTag(ActiveSubagentRingsTags.ring(it.toolCallId)).assertDoesNotExist() }
            assertEquals(5, repository.currentSubagentsWithHistory(parent).count { it.status == "failed" })
        } finally {
            repository.close()
        }
    }

    @Test
    fun streamedTodoUpdatesAndWorkerSuccessRetainTheAuthoredSweep() = runComposeUiTest {
        var entry by mutableStateOf(running(SubagentTodoProgress(1, 3)))
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    ActiveSubagentRings(persistentListOf(entry), now = 1_000L)
                }
            }
        }
        val ring = onNodeWithTag(ActiveSubagentRingsTags.ring("dispatch"))
        assertEquals(1f / 3f, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.0001f)
        runOnIdle { entry = entry.copy(progress = SubagentTodoProgress(2, 3)) }
        assertEquals(2f / 3f, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.0001f)
        runOnIdle { entry = entry.copy(status = ActiveSubagent.Status.COMPLETED) }
        assertEquals(2f / 3f, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0.0001f)
    }

    @Test
    fun workerExitWithoutTodosStillExposesUnknownProgress() = runComposeUiTest {
        var entry by mutableStateOf(running())
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    ActiveSubagentRings(persistentListOf(entry), now = 1_000L)
                }
            }
        }
        val ring = onNodeWithTag(ActiveSubagentRingsTags.ring("dispatch"))
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
        runOnIdle { entry = entry.copy(status = ActiveSubagent.Status.COMPLETED) }
        assertEquals(ProgressBarRangeInfo.Indeterminate, ring.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo])
    }
}
