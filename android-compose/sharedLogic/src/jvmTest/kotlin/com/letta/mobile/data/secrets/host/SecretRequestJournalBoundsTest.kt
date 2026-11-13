package com.letta.mobile.data.secrets.host

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.secrets.journal.InMemorySecretRequestJournal
import com.letta.mobile.data.secrets.request.SecretRequestId
import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.request.SecretRequestProblem
import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestStatus
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import com.letta.mobile.data.secrets.request.SecretRequestTiming
import com.letta.mobile.data.secrets.request.SecretServiceError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What one agent can put in the journal is bounded (pending, created per sliding window, settled
 * kept), and a full journal makes room by pruning settled requests before it refuses a new one.
 */
class SecretRequestJournalBoundsTest {
    private val tooManyRequests =
        SecretServiceResult.Refused(SecretServiceError.INVALID_REQUEST, SecretRequestProblem.TOO_MANY_REQUESTS)

    private suspend fun ServiceHarness.createAndDecline(): SecretRequestId = pending().also { service.cancel(HUMAN, it) }

    private suspend fun ServiceHarness.ask() = service.request(REQUESTER, SecretRequestTestData.request())

    @Test
    fun anAgentMayCreateOnlySoManyRequestsInASlidingWindow() = runTest {
        val harness = ServiceHarness()
        repeat(SecretRequestLimits.MAX_CREATED_PER_WINDOW) {
            harness.createAndDecline()
            harness.clock.nowMs += MINUTE
        }
        assertEquals(tooManyRequests, harness.ask(), "declined requests still count")
        assertTrue(harness.service.request(BYSTANDER, SecretRequestTestData.request()) is SecretServiceResult.Ok, "per agent")

        harness.clock.nowMs = SecretRequestTestData.NOW.toEpochMilliseconds() + SecretRequestLimits.CREATION_WINDOW_MS
        assertTrue(harness.ask() is SecretServiceResult.Ok, "the oldest left the window")
        assertEquals(tooManyRequests, harness.ask(), "only one left it")
    }

    @Test
    fun tooManyPendingIsReportedBeforeTheWindow() = runTest {
        val harness = ServiceHarness()
        repeat(SecretRequestLimits.MAX_PENDING_PER_AGENT) { harness.pending() }
        val refused = harness.ask()
        assertEquals(SecretServiceResult.Refused(SecretServiceError.INVALID_REQUEST, SecretRequestProblem.TOO_MANY_PENDING), refused)
    }

    @Test
    fun eachAgentKeepsOnlyItsNewestSettledRequests() = runTest {
        val harness = ServiceHarness()
        val made = mutableListOf<SecretRequestId>()
        repeat(SecretRequestLimits.MAX_SETTLED_PER_AGENT + 4) { index ->
            val windowFull = index > 0 && index % SecretRequestLimits.MAX_CREATED_PER_WINDOW == 0
            if (windowFull) harness.clock.nowMs += SecretRequestLimits.CREATION_WINDOW_MS
            harness.clock.nowMs += 1
            made += harness.createAndDecline()
        }
        harness.service.list(HUMAN)
        val kept = harness.journal.read().map { it.requestId }
        assertEquals(made.takeLast(SecretRequestLimits.MAX_SETTLED_PER_AGENT), kept)
    }

    @Test
    fun aPolicyMustKeepAtLeastTheWindowsWorthOfSettledRequests() {
        assertFailsWith<IllegalArgumentException> { SecretRequestPolicy(maxCreatedPerWindow = 9, maxSettledPerAgent = 8) }
    }

    @Test
    fun aFullJournalFirstDropsSettledRequestsFromBeforeTheWindow() = runTest {
        val old = (0 until AGENTS - 1).flatMap { agent -> settled(agent, createdAgoMs = HOUR) }
        val recent = settled(AGENTS - 1, createdAgoMs = 0)
        val harness = ServiceHarness(journal = InMemorySecretRequestJournal(old + recent))

        assertTrue(harness.ask() is SecretServiceResult.Ok)

        val stored = harness.journal.read()
        assertEquals(recent.map { it.requestId }.toSet(), stored.filter { it.status.state.isTerminal }.map { it.requestId }.toSet())
        assertEquals(recent.size + 1, stored.size)
    }

    @Test
    fun aJournalFullOfRecentSettledRequestsDropsThemAll() = runTest {
        val recent = (0 until AGENTS).flatMap { agent -> settled(agent, createdAgoMs = 0) }
        val harness = ServiceHarness(journal = InMemorySecretRequestJournal(recent))

        assertTrue(harness.ask() is SecretServiceResult.Ok)
        assertEquals(1, harness.journal.read().size)
    }

    @Test
    fun aJournalFullOfPendingRequestsRefusesAndPrunesNothing() = runTest {
        val perAgent = SecretRequestLimits.MAX_PENDING_PER_AGENT
        val pending = (0 until SecretRequestLimits.MAX_JOURNAL_RECORDS / perAgent).flatMap { agent ->
            (0 until perAgent).map { index -> record(agent, agent * perAgent + index, createdAgoMs = 0, PENDING) }
        }
        val harness = ServiceHarness(journal = InMemorySecretRequestJournal(pending))

        assertEquals(SecretServiceResult.Refused(SecretServiceError.JOURNAL_UNAVAILABLE), harness.ask())
        assertEquals(pending, harness.journal.read())
    }

    /** [SecretRequestLimits.MAX_SETTLED_PER_AGENT] declined requests of agent number [agent]. */
    private fun settled(agent: Int, createdAgoMs: Long): List<SecretRequestRecord> {
        val status = SecretRequestStatus(SecretRequestState.CANCELLED, null, NOW_MS - createdAgoMs / 2)
        return (0 until SecretRequestLimits.MAX_SETTLED_PER_AGENT).map { index ->
            record(agent, agent * SecretRequestLimits.MAX_SETTLED_PER_AGENT + index, createdAgoMs, status)
        }
    }

    private fun record(agent: Int, number: Int, createdAgoMs: Long, status: SecretRequestStatus): SecretRequestRecord {
        val base = SecretRequestTestData.record(SecretRequestTestData.id(number), status)
        val created = NOW_MS - createdAgoMs
        return base.copy(
            details = base.details.copy(requestingAgent = AgentId("agent-seeded-$agent")),
            timing = SecretRequestTiming(created, created + SecretRequestLimits.DEFAULT_TTL_MS),
        )
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        val NOW_MS = SecretRequestTestData.NOW.toEpochMilliseconds()
        val PENDING = SecretRequestStatus(SecretRequestState.PENDING)

        /** Agents whose full share of settled requests fills the journal exactly. */
        val AGENTS = SecretRequestLimits.MAX_JOURNAL_RECORDS / SecretRequestLimits.MAX_SETTLED_PER_AGENT
    }
}
