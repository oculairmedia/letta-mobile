package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.timeline.api.OutboxEvent.DispatchStart
import com.letta.mobile.data.timeline.api.OutboxEvent.Disconnected
import com.letta.mobile.data.timeline.api.OutboxEvent.EchoIngested
import com.letta.mobile.data.timeline.api.OutboxEvent.EchoTimeout
import com.letta.mobile.data.timeline.api.OutboxEvent.ProbeFoundEcho
import com.letta.mobile.data.timeline.api.OutboxEvent.ProbeNoEcho
import com.letta.mobile.data.timeline.api.OutboxEvent.TransportAccepted
import com.letta.mobile.data.timeline.api.OutboxEvent.TransportRejected
import com.letta.mobile.data.timeline.api.OutboxEvent.TurnSettled
import com.letta.mobile.data.timeline.api.OutboxEvent.UserAbort
import com.letta.mobile.data.timeline.api.OutboxEvent.UserDismiss
import com.letta.mobile.data.timeline.api.OutboxEvent.UserResendAnyway
import com.letta.mobile.data.timeline.api.OutboxEvent.UserRetry
import com.letta.mobile.data.timeline.api.OutboxState.Accepted
import com.letta.mobile.data.timeline.api.OutboxState.Confirmed
import com.letta.mobile.data.timeline.api.OutboxState.Dismissed
import com.letta.mobile.data.timeline.api.OutboxState.Dispatching
import com.letta.mobile.data.timeline.api.OutboxState.Failed
import com.letta.mobile.data.timeline.api.OutboxState.Queued
import com.letta.mobile.data.timeline.api.OutboxState.Unknown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class OutboxTransitionsTest {
    private val created = OutboxEntry(
        otid = "otid-1",
        content = "hello",
        attachments = emptyList(),
        createdAt = "2026-01-01T00:00:00Z",
        state = Queued,
        attempt = 0,
        stateSinceMillis = 1_000,
    )

    private fun entry(
        state: OutboxState,
        failure: OutboxFailure? = null,
        resendOnNoEcho: Boolean = false,
        attempt: Long = 0,
    ) = created.copy(state = state, failure = failure, resendOnNoEcho = resendOnNoEcho, attempt = attempt)

    private fun OutboxEntry.on(event: OutboxEvent, now: Long = 2_000, attempt: Long? = this.attempt) =
        OutboxTransitions.next(this, event, now, if (event.fenced) attempt else null)

    private fun OutboxTransition.applied(): OutboxEntry = assertIs<OutboxTransition.Applied>(this).entry

    private fun OutboxTransition.rejection(): OutboxRejection = assertIs<OutboxTransition.Rejected>(this).reason

    /** One entry per distinct starting state, named for failure messages. */
    private val starts = mapOf(
        "Queued" to entry(Queued),
        "Dispatching" to entry(Dispatching),
        "Accepted" to entry(Accepted),
        "Unknown" to entry(Unknown),
        "Unknown+resend" to entry(Unknown, resendOnNoEcho = true),
        "Failed(NotAccepted)" to entry(Failed, OutboxFailure.NotAccepted),
        "Failed(Unconfirmed)" to entry(Failed, OutboxFailure.Unconfirmed),
        "Confirmed" to entry(Confirmed),
        "Dismissed" to entry(Dismissed),
    )

    /** Every (state, event) pair, with the state it lands in or null when the table rejects it. */
    private val table: Map<Pair<String, OutboxEvent>, OutboxState?> = buildMap {
        for (name in starts.keys) for (event in OutboxEvent.entries) put(name to event, null)
        for (name in starts.keys - setOf("Confirmed", "Dismissed")) put(name to EchoIngested, Confirmed)
        put("Queued" to DispatchStart, Dispatching)
        put("Queued" to UserAbort, Dismissed)
        put("Dispatching" to TransportAccepted, Accepted)
        put("Dispatching" to TransportRejected, Failed)
        put("Dispatching" to Disconnected, Unknown)
        put("Accepted" to TurnSettled, Accepted)
        put("Accepted" to EchoTimeout, Unknown)
        put("Accepted" to Disconnected, Unknown)
        for (unknown in listOf("Unknown", "Unknown+resend")) put(unknown to ProbeFoundEcho, Confirmed)
        put("Unknown" to ProbeNoEcho, Failed)
        put("Unknown+resend" to ProbeNoEcho, Queued)
        for (failed in listOf("Failed(NotAccepted)", "Failed(Unconfirmed)")) put(failed to UserDismiss, Dismissed)
        put("Failed(NotAccepted)" to UserRetry, Queued)
        put("Failed(Unconfirmed)" to UserRetry, Unknown)
        for (failed in listOf("Failed(NotAccepted)", "Failed(Unconfirmed)")) put(failed to UserResendAnyway, Queued)
    }

    @Test
    fun everyStateAndEventFollowsTheTable() {
        val mismatches = table.mapNotNull { (key, expected) ->
            val (name, event) = key
            val actual = (starts.getValue(name).on(event) as? OutboxTransition.Applied)?.entry?.state
            if (actual == expected) null else "$name + $event: expected $expected, got $actual"
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun terminalEntriesRejectEveryEvent() {
        for (state in listOf(Confirmed, Dismissed)) {
            for (event in OutboxEvent.entries) {
                assertEquals(OutboxRejection.Terminal, entry(state).on(event).rejection(), "$state + $event")
            }
        }
    }

    @Test
    fun aFencedEventMustNameItsAttempt() {
        assertFailsWith<IllegalArgumentException> { OutboxTransitions.next(entry(Queued), DispatchStart, 0) }
    }

    // #2258: a confirmed retry was dispatched again.
    @Test
    fun aConfirmedPromptIsNeverDispatchedAgain() {
        val confirmed = entry(Accepted).on(EchoIngested).applied()
        assertEquals(OutboxRejection.Terminal, confirmed.on(DispatchStart).rejection())
        assertEquals(OutboxRejection.Terminal, confirmed.on(UserRetry).rejection())
    }

    // #2258: an active retry was retired as failed, and a stale failure overwrote a newer attempt.
    @Test
    fun eventsFromAnOlderAttemptDoNotApply() {
        val retried = entry(Failed, OutboxFailure.NotAccepted).on(UserRetry).applied()
        assertEquals(1, retried.attempt)
        val dispatching = retried.on(DispatchStart).applied()
        for (event in listOf(TransportRejected, Disconnected, TransportAccepted)) {
            assertEquals(OutboxRejection.StaleAttempt, dispatching.on(event, attempt = 0).rejection(), "$event")
        }
        assertEquals(Accepted, dispatching.on(TransportAccepted, attempt = 1).applied().state)
    }

    // #2258: the late "Sent" rule. Acceptance reported after a failure must not resurrect the prompt.
    @Test
    fun lateAcceptanceAfterAFailureIsRejected() {
        val failed = entry(Dispatching).on(TransportRejected).applied()
        assertEquals(OutboxRejection.NotAllowed, failed.on(TransportAccepted).rejection())
    }

    // #2258: recovery failed a send that still had an owner, or failed it before history was exhausted.
    @Test
    fun lostContactOrAMissingEchoProbesInsteadOfFailing() {
        assertEquals(Unknown, entry(Dispatching).on(Disconnected).applied().state)
        assertEquals(Unknown, entry(Accepted).on(Disconnected).applied().state)
        assertEquals(Unknown, entry(Accepted).on(EchoTimeout).applied().state)
        val unknown = entry(Unknown)
        val failing = OutboxEvent.entries.filter { (unknown.on(it) as? OutboxTransition.Applied)?.entry?.state == Failed }
        assertEquals(listOf(ProbeNoEcho), failing)
        val failed = unknown.on(ProbeNoEcho).applied()
        assertEquals(Failed, failed.state)
        assertEquals(OutboxFailure.Unconfirmed, failed.failure)
    }

    // #2258 deferred defect 2: retry kept the original send time, which was also the echo horizon.
    @Test
    fun aRetriedOldPromptGetsAFreshEchoHorizon() {
        val horizon = 300_000L
        val old = entry(Failed, OutboxFailure.NotAccepted).copy(stateSinceMillis = 0)
        val now = 10 * horizon
        val queued = old.on(UserRetry, now).applied()
        val accepted = queued.on(DispatchStart, now).applied().on(TransportAccepted, now).applied()
        val settled = accepted.on(TurnSettled, now + 1).applied()
        assertFalse(settled.echoOverdue(now + 1, horizon))
        assertTrue(settled.echoOverdue(now + 1 + horizon, horizon))
        assertEquals("2026-01-01T00:00:00Z", settled.createdAt)
    }

    @Test
    fun echoOverdueOnlyAppliesToAcceptedEntries() {
        for (state in OutboxState.entries - Accepted) {
            val stale = if (state == Failed) entry(Failed, OutboxFailure.NotAccepted) else entry(state)
            assertFalse(stale.echoOverdue(nowMillis = Long.MAX_VALUE, horizonMillis = 1), "$state")
        }
    }

    @Test
    fun retryingAnUnconfirmedPromptProbesBeforeResending() {
        val retried = entry(Failed, OutboxFailure.Unconfirmed).on(UserRetry).applied()
        assertEquals(Unknown, retried.state)
        assertTrue(retried.resendOnNoEcho)
        assertTrue(retried.isSending)
        assertEquals(Confirmed, retried.on(ProbeFoundEcho).applied().state)
        val requeued = retried.on(ProbeNoEcho).applied()
        assertEquals(Queued, requeued.state)
        assertEquals(retried.attempt + 1, requeued.attempt)
        assertFalse(requeued.resendOnNoEcho)
    }

    // #2258: retry did nothing after a restart.
    @Test
    fun restartProbesWhatMayHaveLeftAndKeepsEverythingElse() {
        assertEquals(Unknown, entry(Dispatching).afterRestart(5_000).state)
        assertEquals(5_000, entry(Accepted).afterRestart(5_000).stateSinceMillis)
        assertEquals(Queued, entry(Queued).afterRestart(5_000).state)
        val failed = entry(Failed, OutboxFailure.NotAccepted).afterRestart(5_000)
        assertEquals(Queued, failed.on(UserRetry).applied().state)
    }

    // #2258: the budget was checked after the transport had already accepted the send.
    @Test
    fun admissionChecksBudgetAndIdentityBeforeAnyDispatch() {
        val live = (1..3).map { created.copy(otid = "otid-live-$it") }
        val candidate = created.copy(otid = "otid-new")
        assertEquals(OutboxAdmission.Admitted, OutboxTransitions.admit(live, candidate, budget = 4))
        assertEquals(OutboxAdmission.OverBudget, OutboxTransitions.admit(live, candidate, budget = 3))
        val settled = live.map { it.copy(state = Confirmed) }
        assertEquals(OutboxAdmission.Admitted, OutboxTransitions.admit(settled, candidate, budget = 3))
        assertEquals(OutboxAdmission.AlreadyQueued, OutboxTransitions.admit(live, live[0], budget = 3))
        assertEquals(
            OutboxAdmission.IdentityConflict,
            OutboxTransitions.admit(live, live[0].copy(content = "other"), budget = 3),
        )
    }

    @Test
    fun presentationFlagsFollowTheState() {
        assertTrue(entry(Queued).canDismiss)
        assertFalse(entry(Dispatching).canDismiss)
        assertTrue(entry(Failed, OutboxFailure.Unconfirmed).canRetry)
        assertFalse(entry(Unknown).canRetry)
        assertFalse(entry(Failed, OutboxFailure.NotAccepted).isSending)
    }

    @Test
    fun entryInvariantsAreEnforced() {
        assertFailsWith<IllegalArgumentException> { entry(Failed) }
        assertFailsWith<IllegalArgumentException> { entry(Queued, OutboxFailure.NotAccepted) }
        assertFailsWith<IllegalArgumentException> { entry(Queued, resendOnNoEcho = true) }
        assertFailsWith<IllegalArgumentException> { created.copy(otid = " ") }
    }

    @Test
    fun resendAnywayQueuesEitherFailureAtTheNextAttempt() {
        for (failure in OutboxFailure.entries) {
            val queued = entry(Failed, failure).on(UserResendAnyway).applied()
            assertEquals(Queued, queued.state)
            assertEquals(1, queued.attempt)
            assertEquals(null, queued.failure)
        }
    }
}
