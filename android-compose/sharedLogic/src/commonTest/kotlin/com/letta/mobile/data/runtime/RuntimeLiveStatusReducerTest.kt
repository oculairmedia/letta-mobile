package com.letta.mobile.data.runtime

import com.letta.mobile.data.presence.ConversationRunState
import com.letta.mobile.data.presence.RunPhase
import com.letta.mobile.data.presence.RunPhaseReducer
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-bzvro.7 / .8 (F07, F08): what the status line says, event by event. */
class RuntimeLiveStatusReducerTest {
    private fun fold(vararg events: RuntimeEventPayload, nowMs: Long = 1_000L): RuntimeLiveStatus =
        events.fold(RuntimeLiveStatus.Idle) { state, event -> RuntimeLiveStatusReducer.reduce(state, event, nowMs) }

    @Test
    fun aLoopPhaseIsShownUntilTheLoopWaitsOnInput() {
        val working = fold(RuntimeEventPayload.LoopPhaseChanged("WAITING_FOR_API_RESPONSE"))
        assertEquals(LoopPhase.WaitingForResponse, working.phase)
        val idle = RuntimeLiveStatusReducer.reduce(working, RuntimeEventPayload.LoopPhaseChanged("WAITING_ON_INPUT"), 2_000L)
        assertTrue(idle.isEmpty)
    }

    @Test
    fun aRetryIsTimedFromWhenItArrivedAndClearsWhenTheLoopMovesOn() {
        val retrying = fold(
            RuntimeEventPayload.RetryNotice(attempt = 2, maxAttempts = 5, delayMs = 4_000L, provider = "anthropic"),
            nowMs = 10_000L,
        )
        assertEquals(LoopPhase.Retrying, retrying.phase)
        val retry = retrying.retry!!
        assertEquals(14_000L, retry.retryAtEpochMs)
        assertEquals("anthropic", retry.provider)
        // The loop status that follows a retry delta keeps it.
        val stillRetrying = RuntimeLiveStatusReducer.reduce(retrying, RuntimeEventPayload.LoopPhaseChanged("RETRYING_API_REQUEST"), 11_000L)
        assertEquals(retry, stillRetrying.retry)
        val sending = RuntimeLiveStatusReducer.reduce(stillRetrying, RuntimeEventPayload.LoopPhaseChanged("SENDING_API_REQUEST"), 14_000L)
        assertNull(sending.retry)
        assertEquals(LoopPhase.SendingRequest, sending.phase)
    }

    @Test
    fun theTurnsTerminalClearsPhaseRetryAndNotice() {
        val busy = fold(
            RuntimeEventPayload.LoopPhaseChanged("PROCESSING_API_RESPONSE"),
            RuntimeEventPayload.RetryNotice(attempt = 1, maxAttempts = 3, delayMs = 1_000L),
            RuntimeEventPayload.StatusNotice("Compacting", "info"),
        )
        listOf(RuntimeRunStatus.Completed, RuntimeRunStatus.Failed, RuntimeRunStatus.Cancelled).forEach { status ->
            val done = RuntimeLiveStatusReducer.reduce(busy, RuntimeEventPayload.RunLifecycleChanged(status), 2_000L)
            assertNull(done.phase, status.name)
            assertNull(done.retry, status.name)
            assertNull(done.notice, status.name)
        }
    }

    @Test
    fun aNoticeKeepsItsLevelAndABlankOneIsIgnored() {
        val warned = fold(RuntimeEventPayload.StatusNotice("Context nearly full", "warning"))
        assertEquals(LiveNotice("Context nearly full", NoticeLevel.Warning), warned.notice)
        assertEquals(warned, RuntimeLiveStatusReducer.reduce(warned, RuntimeEventPayload.StatusNotice("  "), 0L))
    }

    @Test
    fun aCommandEndReplacesItsStart() {
        val status = fold(
            RuntimeEventPayload.CommandStarted("cmd-1", "/compact", slash = true),
            RuntimeEventPayload.CommandFinished("cmd-1", "/compact", "done", success = true, slash = true),
        )
        val command = status.commands.single()
        assertEquals(CommandState.Succeeded, command.state)
        assertEquals("done", command.output)
    }

    @Test
    fun anUnmatchedEndStandsAlone() {
        val status = fold(
            RuntimeEventPayload.CommandStarted("cmd-1", "/doctor"),
            RuntimeEventPayload.CommandFinished("cmd-9", "git status", "boom", success = false, preformatted = true),
        )
        assertEquals(listOf("cmd-1", "cmd-9"), status.commands.map { it.commandId })
        assertEquals(CommandState.Running, status.commands[0].state)
        assertEquals(CommandState.Failed, status.commands[1].state)
        assertTrue(status.commands[1].preformatted)
    }

    @Test
    fun onlyTheNewestCommandsAreKept() {
        val events = (1..5).map { RuntimeEventPayload.CommandStarted("cmd-$it", "c$it") }.toTypedArray()
        assertEquals(listOf("cmd-3", "cmd-4", "cmd-5"), fold(*events).commands.map { it.commandId })
    }

    @Test
    fun aNewMessageDropsFinishedCommandsButKeepsRunningOnes() {
        val status = fold(
            RuntimeEventPayload.CommandStarted("cmd-1", "/compact"),
            RuntimeEventPayload.CommandFinished("cmd-2", "/doctor", "ok", success = true),
            RuntimeEventPayload.LocalUserAppend("local-1", "hi"),
        )
        assertEquals(listOf("cmd-1"), status.commands.map { it.commandId })
    }

    @Test
    fun theRunStateCarriesTheLiveStatusWithoutChangingThePhase() {
        val start = ConversationRunState("conv-1", "agent-1")
        val running = RunPhaseReducer.reduce(start, RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Started), 1L)
        val phased = RunPhaseReducer.reduce(running, RuntimeEventPayload.LoopPhaseChanged("WAITING_FOR_API_RESPONSE"), 2L)
        assertEquals(running.phase, phased.phase)
        assertEquals(RunPhase.QUEUED, phased.phase)
        assertEquals(LoopPhase.WaitingForResponse, phased.live.phase)
        val done = RunPhaseReducer.reduce(phased, RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Completed), 3L)
        assertNull(done.live.phase)
    }
}
