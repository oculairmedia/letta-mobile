package com.letta.mobile.data.controller.reconnect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class WallClockJumpSuspendSignalTest {
    @Test
    fun aWallClockJumpBetweenTicksReadsAsSleepAndWake() = runTest {
        var slept = 0L
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime + slept)
        }
        val events = mutableListOf<SuspendEvent>()
        backgroundScope.launch { WallClockJumpSuspendSignal(clock).events().collect { events += it } }
        runCurrent()

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(emptyList(), events, "ordinary ticks are not a sleep")

        // The machine slept for ten minutes: no tick ran, then the wall clock leapt forward.
        slept += 10.minutes.inWholeMilliseconds
        advanceTimeBy(WallClockJumpSuspendSignal.DEFAULT_TICK)
        runCurrent()

        assertEquals(listOf(SuspendEvent.Suspended, SuspendEvent.Resumed(10.minutes.inWholeMilliseconds)), events)
    }

    @Test
    fun aSmallSchedulingHiccupIsNotASleep() = runTest {
        var drift = 0L
        val clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime + drift)
        }
        val events = mutableListOf<SuspendEvent>()
        backgroundScope.launch { WallClockJumpSuspendSignal(clock).events().collect { events += it } }
        runCurrent()

        drift += 3_000
        advanceTimeBy(WallClockJumpSuspendSignal.DEFAULT_TICK)
        runCurrent()

        assertEquals(emptyList(), events)
    }
}
