package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SubagentEntry
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-fxoew.1 / letta-mobile-ulcg6 RC1: the liveness check runs here
 * (it is disabled in the older suites).
 *
 * The shapes come from the 2026-10-10 desktop log: a post-turn reflection
 * (`subagent-1791641853470-1`, no tool call id) in local-conv-517 was pushed
 * RUNNING once, then the host said nothing for 141s while it thought, and the
 * old watchdog painted it FAILED `stream_timeout`. Silence is not failure:
 * a quiet entry is verified with the host, and only the host ends it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentRepositoryLivenessTest {
    private val scope = SubagentParentScope(PARENT_AGENT, CONVERSATION)
    private val transport = ScopedSubagentListTransport().apply {
        entriesByConversation[CONVERSATION] = listOf(reflection(SubagentStatus.RUNNING))
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

    private suspend fun SubagentRepository.primed(): SubagentRepository = apply {
        activeSubagentsFlow(scope).first { it.isNotEmpty() }
    }

    @Test
    fun listedRunningEntrySurvivesSweepAfterTimeoutWindow() = runTest {
        val repo = repository(backgroundScope).primed()

        now += SubagentRepository.STREAM_TIMEOUT_MS + 1
        val refreshed = repo.refresh().getOrThrow()
        assertEquals(listOf(SubagentStatus.RUNNING), refreshed.map { it.status })
        sweepOnce()

        val entry = repo.currentActiveSubagents(scope).single()
        assertEquals(SubagentStatus.RUNNING, entry.status)
        assertNull(entry.failureReason)
        repo.close()
    }

    @Test
    fun quietEntryTheHostStillRunsIsVerifiedNotFailed() = runTest {
        val repo = repository(backgroundScope).primed()
        val fetchesBefore = transport.requestedConversations.size

        now += QUIET_FROM_LIVE_LOG_MS
        sweepOnce()

        val entry = repo.currentActiveSubagents(scope).single()
        assertEquals(SubagentStatus.RUNNING, entry.status, "quiet is not failed")
        assertNull(entry.failureReason)
        assertEquals(now, entry.lastSeenAtMs, "the host's answer re-stamps liveness")
        assertEquals(listOf(CONVERSATION), transport.requestedConversations.drop(fetchesBefore))
        repo.close()
    }

    @Test
    fun quietEntryTakesTheHostsTerminalState() = runTest {
        val repo = repository(backgroundScope).primed()

        transport.entriesByConversation[CONVERSATION] = listOf(reflection(SubagentStatus.COMPLETED))
        now += QUIET_FROM_LIVE_LOG_MS
        sweepOnce()

        assertEquals(SubagentStatus.COMPLETED, repo.currentActiveSubagents(scope).single().status)
        repo.close()
    }

    @Test
    fun quietEntryTheHostCannotConfirmStaysUntilTheUnverifiableBound() = runTest {
        val repo = repository(backgroundScope).primed()
        transport.failuresBeforeSuccess = Int.MAX_VALUE

        now += QUIET_FROM_LIVE_LOG_MS
        sweepOnce()
        assertEquals(SubagentStatus.RUNNING, repo.currentActiveSubagents(scope).single().status)

        now += SubagentRepository.UNVERIFIABLE_SILENCE_MS
        sweepOnce()
        assertTrue(repo.currentActiveSubagents(scope).isEmpty(), "dropped, never failed")
        repo.close()
    }

    @Test
    fun pushedRunningEntrySurvivesSweepAfterTimeoutWindow() = runTest {
        val repo = repository(backgroundScope).primed()

        now += SubagentRepository.STREAM_TIMEOUT_MS + 1
        transport.events.emit(push(listOf(reflection(SubagentStatus.RUNNING))))
        runCurrent()
        sweepOnce()

        assertEquals(SubagentStatus.RUNNING, repo.currentActiveSubagents(scope).single().status)
        repo.close()
    }

    /** RC2, log line 48106-48111: a push for local-conv-624 retained (and later evicted) a 517 chip. */
    @Test
    fun scopedPushForAnotherConversationLeavesThisOneAlone() = runTest {
        val repo = repository(backgroundScope).primed()

        transport.events.emit(
            push(emptyList(), parentAgentId = PARENT_AGENT, parentConversationId = OTHER_CONVERSATION),
        )
        runCurrent()
        now += ABSENCE_PAST_LINGER_MS
        transport.events.emit(
            push(emptyList(), parentAgentId = PARENT_AGENT, parentConversationId = OTHER_CONVERSATION),
        )
        runCurrent()

        assertEquals(SubagentStatus.RUNNING, repo.currentActiveSubagents(scope).single().status)
        repo.close()
    }

    @Test
    fun unscopedPushFromAnOlderHostIsScopedByItsEntries() = runTest {
        val repo = repository(backgroundScope).primed()
        val elsewhere = running("toolu_other", OTHER_CONVERSATION)

        transport.events.emit(push(listOf(elsewhere)))
        runCurrent()
        now += ABSENCE_PAST_LINGER_MS
        transport.events.emit(push(listOf(elsewhere)))
        runCurrent()

        assertEquals(SubagentStatus.RUNNING, repo.currentActiveSubagents(scope).single().status)
        repo.close()
    }

    /** RC3, log 46756 -> 48111: an ended chip flapped back to running. */
    @Test
    fun endedEntryNeverReturnsToRunning() = runTest {
        val repo = repository(backgroundScope).primed()

        transport.events.emit(push(listOf(reflection(SubagentStatus.COMPLETED))))
        runCurrent()
        transport.events.emit(push(listOf(reflection(SubagentStatus.RUNNING))))
        runCurrent()

        assertEquals(SubagentStatus.COMPLETED, repo.currentActiveSubagents(scope).single().status)
        repo.close()
    }

    private fun reflection(status: String) = SubagentEntry(
        toolCallId = LIVE_REFLECTION_ID,
        subagentType = "Reflection",
        status = status,
        parentAgentId = PARENT_AGENT,
        parentConversationId = CONVERSATION,
    )

    private fun push(
        entries: List<SubagentEntry>,
        parentAgentId: String? = null,
        parentConversationId: String? = null,
    ) = ServerFrame.SubagentsUpdated(
        id = "push-$now",
        ts = "t",
        reason = "registry",
        subagentsActive = entries,
        parentAgentId = parentAgentId,
        parentConversationId = parentConversationId,
    )

    private companion object {
        const val CONVERSATION = "local-conv-517"
        const val OTHER_CONVERSATION = "local-conv-624"
        const val LIVE_REFLECTION_ID = "subagent-1791641853470-1"
        const val START_MS = 1_000L
        const val SWEEP_INTERVAL_MS = 1_000L

        /** The live log's first false failure: merge.timeoutFlippedToFailed ageMs=141263. */
        const val QUIET_FROM_LIVE_LOG_MS = 141_263L

        /** Past the repository's 60s running-absence linger. */
        const val ABSENCE_PAST_LINGER_MS = 61_000L
    }
}
