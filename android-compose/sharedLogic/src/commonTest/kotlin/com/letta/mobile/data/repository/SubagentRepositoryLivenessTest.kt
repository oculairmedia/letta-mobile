package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.ScopedSubagentListTransport.Companion.PARENT_AGENT
import com.letta.mobile.data.repository.ScopedSubagentListTransport.Companion.running
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-fxoew.1: the stream-timeout watchdog runs here (it is disabled
 * in the older suites). A RUNNING entry the host just listed must not be
 * failed; an entry nobody has reported for longer than the timeout must be.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentRepositoryLivenessTest {
    private val scope = SubagentParentScope(PARENT_AGENT, CONVERSATION)
    private val transport = ScopedSubagentListTransport().apply {
        entriesByConversation[CONVERSATION] = listOf(running("toolu_live", CONVERSATION))
        viewedConversation = CONVERSATION
    }
    private var now = START_MS

    private fun repository(owner: CoroutineScope) = SubagentRepository(
        transport = transport,
        scope = owner,
        clock = { now },
        streamTimeoutSweepIntervalMs = SWEEP_INTERVAL_MS,
    )

    private fun TestScope.sweepOnce() {
        advanceTimeBy(SWEEP_INTERVAL_MS + 1)
        runCurrent()
    }

    @Test
    fun listedRunningEntrySurvivesWatchdogSweepAfterTimeoutWindow() = runTest {
        val repo = repository(backgroundScope)
        repo.activeSubagentsFlow(scope).first { it.isNotEmpty() }

        now += SubagentRepository.STREAM_TIMEOUT_MS + 1
        val refreshed = repo.refresh().getOrThrow()
        assertEquals(listOf(SubagentStatus.RUNNING), refreshed.map { it.status })
        sweepOnce()

        val entry = repo.currentActiveSubagents(scope).single()
        assertEquals(SubagentStatus.RUNNING, entry.status)
        assertEquals(null, entry.failureReason)
        repo.close()
    }

    @Test
    fun silentRunningEntryTimesOutAfterStreamTimeout() = runTest {
        val repo = repository(backgroundScope)
        repo.activeSubagentsFlow(scope).first { it.isNotEmpty() }

        now += SubagentRepository.STREAM_TIMEOUT_MS + 1
        sweepOnce()

        val entry = repo.currentActiveSubagents(scope).single()
        assertEquals(SubagentStatus.FAILED, entry.status)
        assertEquals(SubagentRepository.FAILURE_REASON_STREAM_TIMEOUT, entry.failureReason)
        repo.close()
    }

    @Test
    fun pushedRunningEntrySurvivesWatchdogSweepAfterTimeoutWindow() = runTest {
        val repo = repository(backgroundScope)
        repo.activeSubagentsFlow(scope).first { it.isNotEmpty() }

        now += SubagentRepository.STREAM_TIMEOUT_MS + 1
        transport.events.emit(
            ServerFrame.SubagentsUpdated(
                id = "push",
                ts = "t",
                subagentsActive = listOf(running("toolu_live", CONVERSATION)),
            ),
        )
        runCurrent()
        sweepOnce()

        assertEquals(SubagentStatus.RUNNING, repo.currentActiveSubagents(scope).single().status)
        repo.close()
    }

    private companion object {
        const val CONVERSATION = "conv-live"
        const val START_MS = 1_000L
        const val SWEEP_INTERVAL_MS = 1_000L
    }
}
