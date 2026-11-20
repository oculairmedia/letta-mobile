@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.SubagentEntry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rings' clock keeps ticking while a ring that will be drawn is live. A plan-less run draws no
 * ring, so it never keeps the clock going; the agent's own planned ring does, whatever the snapshot holds.
 */
class SubagentRingsClockTest {
    /** A running dispatch with no plan: filtered out of the rings, so it must not decide the clock. */
    private fun planLess(id: String) = SubagentEntry(
        toolCallId = id,
        description = "work $id",
        subagentType = "General-purpose",
        status = "running",
    ).toActiveSubagent()

    private fun ownPlan() = ActiveSubagent(
        id = ActiveSubagent.SELF_ID,
        description = "Your plan",
        subagentType = "self",
        status = ActiveSubagent.Status.RUNNING,
        isSelf = true,
        progress = SubagentTodoProgress(completed = 1, total = 3),
    )

    /** A fake wall clock that counts its readings: each tick of the rings' loop reads it once. */
    private class CountingClock {
        var reads = 0
            private set

        fun read(): Long = ++reads * 1_000L
    }

    @Test
    fun aPlannedSelfRingKeepsTheClockTickingWhenEverySnapshotRunIsPlanLess() = runComposeUiTest {
        mainClock.autoAdvance = false
        val clock = CountingClock()
        val snapshot: ImmutableList<ActiveSubagent> = listOf(planLess("a"), planLess("b")).toImmutableList()
        val self = ownPlan()
        setContent {
            MaterialTheme { rememberSubagentRingsState(snapshot, self, clock::read) }
        }
        mainClock.advanceTimeBy(1_000)
        val afterFirstSecond = clock.reads
        mainClock.advanceTimeBy(5_000)
        assertTrue(
            clock.reads >= afterFirstSecond + 4,
            "the clock stopped with a planned self ring live: $afterFirstSecond reads after 1s, ${clock.reads} after 6s",
        )
    }

    @Test
    fun theClockStartsWhenTheSelfPlanAppearsOverAnAlreadyIdleSnapshot() = runComposeUiTest {
        mainClock.autoAdvance = false
        val clock = CountingClock()
        val snapshot: ImmutableList<ActiveSubagent> = persistentListOf(planLess("a"))
        var self by mutableStateOf<ActiveSubagent?>(null)
        setContent {
            MaterialTheme { rememberSubagentRingsState(snapshot, self, clock::read) }
        }
        mainClock.advanceTimeBy(3_000)
        val idleReads = clock.reads
        mainClock.advanceTimeBy(3_000)
        assertEquals(idleReads, clock.reads, "nothing live yet: the clock must be still")
        self = ownPlan()
        mainClock.advanceTimeBy(1_000)
        val afterPlanAppears = clock.reads
        mainClock.advanceTimeBy(5_000)
        assertTrue(
            clock.reads >= afterPlanAppears + 4,
            "the self plan appeared but the clock never started: $afterPlanAppears reads, then ${clock.reads}",
        )
    }

    @Test
    fun aSnapshotWithNoPlannedRingAnywhereLeavesTheClockStill() = runComposeUiTest {
        mainClock.autoAdvance = false
        val clock = CountingClock()
        val snapshot: ImmutableList<ActiveSubagent> = persistentListOf(planLess("a"))
        setContent {
            MaterialTheme { rememberSubagentRingsState(snapshot, null, clock::read) }
        }
        mainClock.advanceTimeBy(1_000)
        val settled = clock.reads
        mainClock.advanceTimeBy(10_000)
        assertEquals(settled, clock.reads, "a plan-less run draws no ring and must not keep the clock ticking")
    }
}
