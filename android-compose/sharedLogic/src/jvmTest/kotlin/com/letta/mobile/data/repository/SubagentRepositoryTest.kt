package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import org.junit.Assert.assertEquals
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
}