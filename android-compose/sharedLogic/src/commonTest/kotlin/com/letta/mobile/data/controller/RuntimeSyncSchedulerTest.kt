package com.letta.mobile.data.controller

import com.letta.mobile.data.controller.RuntimeSyncScheduler.SyncReason
import com.letta.mobile.data.controller.fanout.StreamIntegrityMonitor
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerLoopStatus
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/** letta-mobile-bzvro.6 (F06): recovery syncs are throttled; the cadence follows the loop. */
class RuntimeSyncSchedulerTest {
    private val runtime = AppServerRuntimeScope("agent-1", "conv-1")
    private var now = 100_000L
    private val synced = mutableListOf<Pair<String, SyncReason>>()
    private var watched = listOf(runtime)
    private val scheduler = RuntimeSyncScheduler(
        runtimes = { watched },
        sync = { scope, reason -> synced += scope.conversationId to reason },
        clock = { now },
    )

    @Test
    fun aGapSyncsRightAwayWithApprovalRecovery() = runTest {
        scheduler.request(SyncReason.Gap)
        scheduler.tick()
        assertEquals(listOf("conv-1" to SyncReason.Gap), synced)
        assertTrue(SyncReason.Gap.recoverApprovals)
    }

    @Test
    fun aBurstOfGapsIsAtMostOneSyncPerSecond() = runTest {
        repeat(5) {
            scheduler.request(SyncReason.Gap)
            scheduler.tick()
            now += 200
        }
        assertEquals(1, synced.size)
        now = 100_000L + 1_000L
        scheduler.tick()
        // The coalesced request goes out once the window opens, and only once.
        assertEquals(2, synced.size)
        scheduler.tick()
        assertEquals(2, synced.size)
    }

    @Test
    fun aBusyLoopSyncsEveryFiveSecondsAndAnIdleOneEveryThirty() = runTest {
        scheduler.tick() // first sight starts the cadence
        scheduler.noteLoopStatus(runtime, "WAITING_FOR_API_RESPONSE")
        now += 4_000
        scheduler.tick()
        assertEquals(0, synced.size)
        now += 1_000
        scheduler.tick()
        assertEquals(listOf("conv-1" to SyncReason.Periodic), synced)

        scheduler.noteLoopStatus(runtime, "WAITING_ON_INPUT")
        now += 5_000
        scheduler.tick()
        assertEquals(1, synced.size)
        now += 25_000
        scheduler.tick()
        assertEquals(2, synced.size)
        assertEquals(false, SyncReason.Periodic.recoverApprovals)
    }

    @Test
    fun onlyWatchedRuntimesAreSynced() = runTest {
        watched = emptyList()
        scheduler.request(SyncReason.Foreground)
        scheduler.tick()
        assertEquals(emptyList(), synced)
        watched = listOf(runtime, AppServerRuntimeScope("agent-1", "conv-2"))
        scheduler.request(SyncReason.Foreground)
        scheduler.tick()
        assertEquals(setOf("conv-1", "conv-2"), synced.map { it.first }.toSet())
    }

    @Test
    fun aFailedSyncDoesNotStopTheNextOne() = runTest {
        var fail = true
        val failing = RuntimeSyncScheduler(
            runtimes = { watched },
            sync = { scope, reason ->
                if (fail) error("socket closed")
                synced += scope.conversationId to reason
            },
            clock = { now },
        )
        failing.request(SyncReason.Gap)
        failing.tick()
        fail = false
        now += 1_000
        failing.request(SyncReason.Gap)
        failing.tick()
        assertEquals(listOf("conv-1" to SyncReason.Gap), synced)
    }

    @Test
    fun theMonitorTurnsOneTwoFourIntoOneSyncAndIgnoresDuplicates() = runTest {
        val monitor = StreamIntegrityMonitor(scheduler, detectGaps = true)
        listOf(1L, 2L, 2L, 4L).forEach { seq -> monitor.observe(delta(seq)) }
        scheduler.tick()
        assertEquals(listOf("conv-1" to SyncReason.Gap), synced)
    }

    @Test
    fun withoutGapDetectionTheMonitorOnlyFollowsTheLoop() = runTest {
        val monitor = StreamIntegrityMonitor(scheduler, detectGaps = false)
        listOf(1L, 5L, 9L).forEach { seq -> monitor.observe(delta(seq)) }
        monitor.observe(loopStatus("PROCESSING_API_RESPONSE"))
        scheduler.tick()
        assertEquals(emptyList(), synced)
        now += 5_000
        scheduler.tick()
        assertEquals(listOf("conv-1" to SyncReason.Periodic), synced)
    }

    @Test
    fun aGenerationChangeResetsGapTracking() = runTest {
        val monitor = StreamIntegrityMonitor(scheduler, detectGaps = true)
        monitor.observe(delta(1, generation = 1))
        monitor.observe(delta(9, generation = 2))
        scheduler.tick()
        assertEquals(emptyList(), synced)
    }

    @Test
    fun aCadenceSyncWaitsWhileTheBudgetSaysNoAndGoesOutOnceItAllows() = runTest {
        var allowed = false
        var asked = 0
        val budgeted = budgetedScheduler(SyncBudgetPolicy { asked++; allowed })
        budgeted.tick() // first sight starts the cadence
        assertEquals(0, asked, "the budget is only asked when a cadence sync is due")
        now += 30_000
        budgeted.tick()
        assertEquals(emptyList(), synced)
        assertEquals(1, asked)
        now += 1_000
        allowed = true
        budgeted.tick()
        assertEquals(listOf("conv-1" to SyncReason.Periodic), synced)
    }

    @Test
    fun aGapResyncsEvenWhenTheBudgetSaysNo() = runTest {
        val budgeted = budgetedScheduler(SyncBudgetPolicy { false })
        val monitor = StreamIntegrityMonitor(budgeted, detectGaps = true)
        listOf(1L, 2L, 4L).forEach { seq -> monitor.observe(delta(seq)) }
        budgeted.tick()
        assertEquals(listOf("conv-1" to SyncReason.Gap), synced)
        now += 60_000
        budgeted.tick()
        assertEquals(1, synced.size, "no cadence sync while the budget says no")
    }

    @Test
    fun thePhoneBudgetSuppressesCadenceOnMeteredOrUnpluggedWithTheScreenOff() {
        fun allows(unmetered: Boolean, charging: Boolean, screenOn: Boolean) =
            DeviceConditionsSyncBudget { DeviceSyncConditions(unmetered, charging, screenOn) }.allowsPeriodicSync()

        assertFalse(allows(unmetered = false, charging = true, screenOn = true), "metered")
        assertFalse(allows(unmetered = false, charging = false, screenOn = false), "metered and unplugged")
        assertFalse(allows(unmetered = true, charging = false, screenOn = false), "unplugged, screen off")
        assertTrue(allows(unmetered = true, charging = true, screenOn = false), "unmetered and charging")
        assertTrue(allows(unmetered = true, charging = false, screenOn = true), "unmetered, screen on")
    }

    @Test
    fun aMeteredPhoneGetsGapResyncsButNoCadence() = runTest {
        val budgeted = budgetedScheduler(
            DeviceConditionsSyncBudget { DeviceSyncConditions(unmeteredNetwork = false, charging = true, screenOn = true) },
        )
        budgeted.tick()
        now += 30_000
        budgeted.tick()
        assertEquals(emptyList(), synced)
        budgeted.request(SyncReason.Gap)
        budgeted.tick()
        assertEquals(listOf("conv-1" to SyncReason.Gap), synced)
    }

    @Test
    fun anUnmeteredChargingPhoneKeepsTheCadence() = runTest {
        val budgeted = budgetedScheduler(
            DeviceConditionsSyncBudget { DeviceSyncConditions(unmeteredNetwork = true, charging = true, screenOn = false) },
        )
        budgeted.tick()
        now += 30_000
        budgeted.tick()
        assertEquals(listOf("conv-1" to SyncReason.Periodic), synced)
    }

    private fun budgetedScheduler(budget: SyncBudgetPolicy) = RuntimeSyncScheduler(
        runtimes = { watched },
        sync = { scope, reason -> synced += scope.conversationId to reason },
        clock = { now },
        budget = budget,
    )

    private fun delta(seq: Long, generation: Long = 1) = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = seq,
            emittedAt = "t",
            idempotencyKey = "k-$seq",
            delta = buildJsonObject { },
        ),
        raw = JsonObject(emptyMap()),
        connectionGeneration = generation,
    )

    private fun loopStatus(status: String) = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = AppServerInboundFrame.UpdateLoopStatus(
            runtime = runtime,
            eventSeq = 1,
            emittedAt = "t",
            idempotencyKey = "loop",
            loopStatus = AppServerLoopStatus(status = status),
        ),
        raw = JsonObject(emptyMap()),
    )
}
