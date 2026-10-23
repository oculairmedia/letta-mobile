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
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reads the production composable's target sweep, also exposed as accessible progress. */
class ActiveSubagentProgressUiTest {
    private fun running(progress: SubagentTodoProgress? = null) = ActiveSubagent(
        id = "dispatch", description = "Test progress", subagentType = "general-purpose",
        status = ActiveSubagent.Status.RUNNING, progress = progress,
    )

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
