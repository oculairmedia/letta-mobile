package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for letta-mobile-ve08r AC#4 stream-timeout watchdog.
 *
 * These tests exercise the public-facing constants and the additive model fields
 * the watchdog relies on. Full integration coverage (the actual sweepStreamTimeouts
 * round-trip through a live MutableStateFlow) requires a more elaborate
 * `runTest { advanceTimeUntilIdle }` harness than this short-form suite carries;
 * that integration lives in the manual on-device gate.
 */
class SubagentRepositoryTest {

    /**
     * Construct a RUNNING SubagentEntry with explicit lastSeenAtMs.
     */
    private fun runningEntry(toolCallId: String, lastSeenAtMs: Long): SubagentEntry =
        SubagentEntry(
            toolCallId = toolCallId,
            description = "test",
            subagentType = "test",
            status = SubagentStatus.RUNNING,
            taskId = null,
            subagentAgentId = null,
            subagentConversationId = null,
            parentRunId = null,
            parentAgentId = null,
            parentConversationId = null,
            startedAt = null,
            todoProgress = null,
            lastSeenAtMs = lastSeenAtMs,
            failureReason = null,
        )

    @Test
    fun streamTimeoutConstantIs120Seconds() {
        assertEquals(120_000L, SubagentRepository.STREAM_TIMEOUT_MS)
    }

    @Test
    fun failureReasonStringIsExactlyStreamTimeout() {
        assertEquals("stream_timeout", SubagentRepository.FAILURE_REASON_STREAM_TIMEOUT)
    }

    @Test
    fun runningEntryExposesLastSeenAtMsField() {
        // Documents the additive field that the watchdog reads. Without lastSeenAtMs,
        // every RUNNING entry would be eligible for immediate timeout eviction.
        val now = 1_700_000_000_000L
        val entry = runningEntry("t-1", lastSeenAtMs = now)
        assertEquals(now, entry.lastSeenAtMs)
        assertEquals(SubagentStatus.RUNNING, entry.status)
        assertEquals(null, entry.failureReason)
    }

    /**
     * letta-mobile-g70jb.1: an HTTP (non-Iroh) desktop backend has no channel
     * transport, so the session graph binds the registry to NoOpChannelTransport.
     * The registry must degrade to an empty list, never throw.
     */
    @Test
    fun noOpTransportDegradesToAnEmptyRegistry() = runBlocking {
        val repository = SubagentRepository(NoOpChannelTransport(), includeAll = true)
        try {
            val refreshed = repository.refresh()
            assertTrue(refreshed.exceptionOrNull() is UnsupportedOperationException)
            assertTrue(repository.todos("tool-call-1").exceptionOrNull() is UnsupportedOperationException)
            val scope = SubagentParentScope(parentAgentId = "agent-1", parentConversationId = "conv-1")
            assertEquals(emptyList<SubagentEntry>(), repository.activeSubagentsFlow(scope).first())
            assertEquals(emptyList<SubagentEntry>(), repository.currentActiveSubagents(scope))
        } finally {
            repository.close()
        }
    }
}
