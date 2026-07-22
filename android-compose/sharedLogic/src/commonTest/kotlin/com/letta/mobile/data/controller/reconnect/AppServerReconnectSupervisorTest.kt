package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.controller.AppServerControllerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppServerReconnectSupervisorTest {

    private fun events() = mutableListOf<AppServerReconnectSupervisor.SupervisorEvent>()

    // Records requested backoff waits and returns immediately, so the schedule
    // is asserted deterministically without real time.
    private class RecordingDelay {
        val waits = mutableListOf<Long>()
        val provider: suspend (Long) -> Unit = { waits += it }
    }

    private val okResult = ReconnectResult(reconnectedCount = 1, errors = emptyList())

    @Test
    fun retriesWhileDroppedWithGrowingBackoffThenStopsOnConnected() = runTest {
        val states = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
        val delayRec = RecordingDelay()
        val evs = events()
        var reconnects = 0
        val supervisor = AppServerReconnectSupervisor(
            connectionState = states,
            reconnect = {
                reconnects++
                // After 3 attempts, simulate the server coming back so the
                // self-driving retry loop exits.
                if (reconnects >= 3) states.value = AppServerControllerState.Connected
                okResult
            },
            backoff = FullJitterBackoff(baseDelayMs = 100, maxDelayMs = 10_000, multiplier = 2.0),
            random = Random(1),
            delayProvider = delayRec.provider,
            onEvent = { evs += it },
        )
        val job = launch { supervisor.run() }
        runCurrent()

        states.value = AppServerControllerState.Disconnected("drop")
        advanceUntilIdle()

        assertEquals(3, reconnects)
        assertEquals(3, delayRec.waits.size)
        val backoff = FullJitterBackoff(baseDelayMs = 100, maxDelayMs = 10_000, multiplier = 2.0)
        delayRec.waits.forEachIndexed { i, w ->
            assertTrue(w in 0..backoff.ceilingMs(i), "wait $w exceeds ceiling for attempt $i")
        }
        val scheduled = evs.filterIsInstance<AppServerReconnectSupervisor.SupervisorEvent.Scheduled>()
        assertEquals(listOf(0, 1, 2), scheduled.map { it.attempt })
        job.cancel()
    }

    @Test
    fun terminalStateStopsWithoutReconnecting() = runTest {
        val states = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
        val delayRec = RecordingDelay()
        val evs = events()
        var reconnects = 0
        val supervisor = AppServerReconnectSupervisor(
            connectionState = states,
            reconnect = { reconnects++; okResult },
            delayProvider = delayRec.provider,
            isTerminal = { it is AppServerControllerState.Error },
            onEvent = { evs += it },
        )
        val job = launch { supervisor.run() }
        runCurrent()

        states.value = AppServerControllerState.Error("auth failed")
        advanceUntilIdle()

        assertEquals(0, reconnects, "terminal state must not trigger reconnect")
        assertTrue(evs.any { it is AppServerReconnectSupervisor.SupervisorEvent.TerminalStop })
        assertTrue(job.isCompleted, "supervisor loop must end on terminal state")
    }

    @Test
    fun maxAttemptsGivesUp() = runTest {
        val states = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
        val delayRec = RecordingDelay()
        val evs = events()
        var reconnects = 0
        val supervisor = AppServerReconnectSupervisor(
            connectionState = states,
            reconnect = { reconnects++; okResult }, // server never recovers
            maxAttempts = 2,
            delayProvider = delayRec.provider,
            onEvent = { evs += it },
        )
        val job = launch { supervisor.run() }
        runCurrent()

        states.value = AppServerControllerState.Disconnected("drop")
        advanceUntilIdle()

        assertEquals(2, reconnects)
        assertTrue(evs.any { it is AppServerReconnectSupervisor.SupervisorEvent.GaveUp })
        assertTrue(job.isCompleted, "supervisor must stop after maxAttempts")
    }

    @Test
    fun stableReconnectResetsAttemptCounter() = runTest {
        val states = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
        val delayRec = RecordingDelay()
        val evs = events()
        var reconnects = 0
        val supervisor = AppServerReconnectSupervisor(
            connectionState = states,
            reconnect = {
                reconnects++
                // Recover after the first attempt of each drop sequence.
                states.value = AppServerControllerState.Connected
                okResult
            },
            backoff = FullJitterBackoff(baseDelayMs = 100, maxDelayMs = 10_000, multiplier = 2.0),
            stableConnectedResetMs = 0, // reset immediately once Connected
            random = Random(1),
            delayProvider = delayRec.provider,
            onEvent = { evs += it },
        )
        val job = launch { supervisor.run() }
        runCurrent()

        // First drop -> attempt 0 -> recovers.
        states.value = AppServerControllerState.Disconnected("d1"); advanceUntilIdle()
        // Second drop -> counter reset -> attempt 0 again (not 1).
        states.value = AppServerControllerState.Disconnected("d2"); advanceUntilIdle()

        val scheduled = evs.filterIsInstance<AppServerReconnectSupervisor.SupervisorEvent.Scheduled>()
        assertEquals(listOf(0, 0), scheduled.map { it.attempt },
            "attempt counter must reset to 0 after a stable reconnect")
        assertTrue(evs.any { it is AppServerReconnectSupervisor.SupervisorEvent.AttemptsReset })
        job.cancel()
    }

    @Test
    fun reconnectExceptionIsCaughtAndLoopContinues() = runTest {
        val states = MutableStateFlow<AppServerControllerState>(AppServerControllerState.Connected)
        val delayRec = RecordingDelay()
        val evs = events()
        var calls = 0
        val supervisor = AppServerReconnectSupervisor(
            connectionState = states,
            reconnect = {
                calls++
                if (calls == 1) throw RuntimeException("reconnect boom")
                states.value = AppServerControllerState.Connected // recover on 2nd
                okResult
            },
            delayProvider = delayRec.provider,
            onEvent = { evs += it },
        )
        val job = launch { supervisor.run() }
        runCurrent()

        states.value = AppServerControllerState.Disconnected("drop")
        advanceUntilIdle()

        assertEquals(2, calls)
        assertTrue(evs.any { it is AppServerReconnectSupervisor.SupervisorEvent.AttemptFailed })
        assertTrue(evs.any { it is AppServerReconnectSupervisor.SupervisorEvent.AttemptCompleted })
        job.cancel()
    }
}
