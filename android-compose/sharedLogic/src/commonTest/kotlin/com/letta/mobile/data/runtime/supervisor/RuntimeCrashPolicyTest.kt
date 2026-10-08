package com.letta.mobile.data.runtime.supervisor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuntimeCrashPolicyTest {
    private val crash = RuntimeExit(exitCode = 1, intentional = false)

    @Test
    fun backoffDoublesFromTwoSecondsAndCapsAtThirty() {
        val policy = RuntimeCrashPolicy()

        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map(policy::delayFor))
    }

    @Test
    fun consecutiveCrashesRestartWithGrowingDelaysThenGiveUpAtFiveInAMinute() {
        val policy = RuntimeCrashPolicy()
        var now = 0L
        val decisions = (1..5).map {
            policy.onStarted(now)
            now += 1_000
            policy.onExit(now, crash).also { decision ->
                if (decision is RuntimeCrashDecision.Restart) now += decision.delayMs
            }
        }

        assertEquals(
            listOf(
                RuntimeCrashDecision.Restart(2_000, 1),
                RuntimeCrashDecision.Restart(4_000, 2),
                RuntimeCrashDecision.Restart(8_000, 3),
                RuntimeCrashDecision.Restart(16_000, 4),
                RuntimeCrashDecision.GiveUp(5),
            ),
            decisions,
        )
    }

    @Test
    fun crashesSpreadBeyondTheWindowDoNotGiveUp() {
        val policy = RuntimeCrashPolicy()
        var now = 0L
        repeat(8) {
            policy.onStarted(now)
            now += 20_000
            assertIs<RuntimeCrashDecision.Restart>(policy.onExit(now, crash))
        }
    }

    @Test
    fun aMinuteOfHealthyUptimeResetsTheCounters() {
        val policy = RuntimeCrashPolicy()
        policy.onStarted(0)
        policy.onExit(1_000, crash)
        policy.onStarted(3_000)
        policy.onExit(4_000, crash)
        assertEquals(2, policy.consecutiveCrashes)

        policy.onStarted(10_000)
        val decision = policy.onExit(10_000 + 60_000, crash)

        assertEquals(RuntimeCrashDecision.Restart(2_000, 1), decision)
    }

    @Test
    fun intentionalStopsAndCleanExitsNeverRestart() {
        val policy = RuntimeCrashPolicy()
        policy.onStarted(0)
        assertIs<RuntimeCrashDecision.Ignore>(policy.onExit(10, RuntimeExit(exitCode = 137, intentional = true)))
        policy.onStarted(20)
        assertIs<RuntimeCrashDecision.Ignore>(policy.onExit(30, RuntimeExit(exitCode = 0, intentional = false)))
        assertEquals(0, policy.consecutiveCrashes)
    }

    @Test
    fun suspendThenCrashThenResumeCountsNoCrash() {
        val policy = RuntimeCrashPolicy()
        policy.onStarted(0)
        policy.onExit(1_000, crash)
        policy.onStarted(3_000)
        assertEquals(1, policy.consecutiveCrashes)

        policy.onSuspended()
        val duringSleep = policy.onExit(5_000, crash)
        policy.onResumed()

        assertEquals(RuntimeCrashDecision.Ignore("system suspended"), duringSleep)
        assertEquals(0, policy.consecutiveCrashes, "resume clears crash state")
        policy.onStarted(6_000)
        assertEquals(RuntimeCrashDecision.Restart(2_000, 1), policy.onExit(7_000, crash))
    }

    @Test
    fun resetForgetsPastCrashes() {
        val policy = RuntimeCrashPolicy()
        repeat(4) { policy.onExit(it * 10L, crash) }
        policy.reset()

        assertEquals(RuntimeCrashDecision.Restart(2_000, 1), policy.onExit(100, crash))
    }

    @Test
    fun stderrRingKeepsTheLastTenLines() {
        val ring = StderrRing()
        repeat(15) { ring.add("line $it") }

        assertEquals((5 until 15).map { "line $it" }, ring.snapshot())
        ring.clear()
        assertEquals(emptyList(), ring.snapshot())
    }

    @Test
    fun healthNeedsForceRestartOnlyWhenNothingWillRestartIt() {
        assertEquals(false, RuntimeHealth(active = true).needsForceRestart)
        assertEquals(false, RuntimeHealth(restartPending = true, lastExitCode = 1, stoppedUnexpectedly = true).needsForceRestart)
        assertEquals(true, RuntimeHealth(gaveUp = true, lastExitCode = 1).needsForceRestart)
        assertEquals(true, RuntimeHealth(lastExitCode = 0, stoppedUnexpectedly = true).needsForceRestart, "an unasked-for clean exit")
        assertEquals(false, RuntimeHealth(lastExitCode = 1).needsForceRestart, "our own stop after an old crash")
        assertEquals(false, RuntimeHealth().needsForceRestart, "never started is not a failure")
    }
}
