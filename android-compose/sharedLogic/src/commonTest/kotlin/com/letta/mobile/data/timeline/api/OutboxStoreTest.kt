package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.InMemoryTimelineStore
import com.letta.mobile.data.timeline.OutboxStore
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class OutboxStoreTest {
    private val scope = TimelineScope("backend", "conversation")
    private val ledger = InMemoryTimelineStore()
    private var now = 1_000L
    private val outbox = OutboxStore(ledger) { now }

    private fun prompt(otid: String, content: String = "hello") =
        OutboxEntry.queued(otid, content, listOf(MessageContentPart.Image("aGVsbG8=", "image/png")), SENT_AT, now)

    @Test
    fun admittedPromptsSurviveARestartWithTheirAttachments() = runTest {
        val first = prompt("otid-1")
        assertEquals(OutboxAdmission.Admitted, outbox.enqueue(scope, first))
        assertEquals(OutboxAdmission.AlreadyQueued, outbox.enqueue(scope, first))
        assertEquals(OutboxAdmission.IdentityConflict, outbox.enqueue(scope, first.copy(content = "other")))
        assertEquals(listOf(first), OutboxStore(ledger) { now }.load(scope))
    }

    @Test
    fun aFullOutboxRefusesBeforeAnythingIsWritten() = runTest {
        repeat(OutboxTransitions.BUDGET) { assertEquals(OutboxAdmission.Admitted, outbox.enqueue(scope, prompt("otid-$it"))) }
        val revision = ledger.current.revision
        assertEquals(OutboxAdmission.OverBudget, outbox.enqueue(scope, prompt("otid-over")))
        assertEquals(revision, ledger.current.revision)
        assertEquals(OutboxTransitions.BUDGET, outbox.load(scope).size)
    }

    @Test
    fun anOversizedPromptLeavesTheOutboxUntouched() = runTest {
        outbox.enqueue(scope, prompt("otid-1"))
        assertFailsWith<IllegalArgumentException> {
            outbox.enqueue(scope, prompt("otid-big", content = "x".repeat(OUTBOX_MAX_BYTES + 1)))
        }
        assertEquals(listOf("otid-1"), outbox.load(scope).map { it.otid })
    }

    @Test
    fun transitionsAreComparedAndSetByAttempt() = runTest {
        outbox.enqueue(scope, prompt("otid-1"))
        now = 2_000
        assertIs<OutboxTransition.Applied>(outbox.transition(scope, "otid-1", OutboxEvent.DispatchStart, attempt = 0))
        val stale = outbox.transition(scope, "otid-1", OutboxEvent.TransportRejected, attempt = 7)
        assertEquals(OutboxRejection.StaleAttempt, assertIs<OutboxTransition.Rejected>(stale).reason)
        val stored = outbox.load(scope).single()
        assertEquals(OutboxState.Dispatching, stored.state)
        assertEquals(2_000, stored.stateSinceMillis)
    }

    @Test
    fun settledEntriesAreRemovedAndLaterEventsFindNothing() = runTest {
        outbox.enqueue(scope, prompt("otid-1"))
        outbox.enqueue(scope, prompt("otid-2"))
        outbox.transition(scope, "otid-1", OutboxEvent.EchoIngested)
        outbox.transition(scope, "otid-2", OutboxEvent.UserAbort)
        assertEquals(emptyList(), outbox.load(scope))
        assertNull(ledger.evidence["outbox/v2"])
        assertNull(outbox.transition(scope, "otid-1", OutboxEvent.DispatchStart, attempt = 0))
    }

    @Test
    fun restartTurnsAnythingThatMayHaveLeftIntoAProbe() = runTest {
        outbox.enqueue(scope, prompt("queued"))
        outbox.enqueue(scope, prompt("accepted"))
        outbox.transition(scope, "accepted", OutboxEvent.DispatchStart, attempt = 0)
        outbox.transition(scope, "accepted", OutboxEvent.TransportAccepted, attempt = 0)
        now = 9_000
        val recovered = OutboxStore(ledger) { now }.recoverAfterRestart(scope)
        assertEquals(listOf(OutboxState.Queued, OutboxState.Unknown), recovered.map { it.state })
        assertEquals(9_000, recovered[1].stateSinceMillis)
        assertEquals(recovered, outbox.load(scope))
    }

    @Test
    fun theLegacyPendingStoreIsConvertedOnTheFirstWrite() = runTest {
        legacy(legacyRecord("sending", "Sending", "1970-01-01T00:00:01Z") + "," + legacyRecord("failed", "Failed", "not a time"))
        now = 5_000

        val read = outbox.load(scope)
        assertEquals(listOf(OutboxState.Dispatching, OutboxState.Failed), read.map { it.state })
        assertEquals(listOf(null, OutboxFailure.Unconfirmed), read.map { it.failure })
        assertEquals(listOf("1970-01-01T00:00:01Z", "not a time"), read.map { it.createdAt })
        assertTrue("pending/local/v1" in ledger.evidence)

        val recovered = outbox.recoverAfterRestart(scope)
        assertTrue("pending/local/v1" !in ledger.evidence)
        assertEquals(listOf(OutboxState.Unknown, OutboxState.Failed), recovered.map { it.state })
        assertEquals(recovered, outbox.load(scope))
    }

    private fun legacy(records: String) =
        ledger.putEvidence("pending/local/v1", """{"version":1,"records":[$records]}""".encodeToByteArray())

    private fun legacyRecord(otid: String, delivery: String, sentAt: String, attachments: String = "", attempt: Int = 0) =
        """{"otid":"$otid","content":"c-$otid","attachments":[$attachments],"sentAt":"$sentAt",""" +
            """"delivery":"$delivery","attempt":$attempt}"""

    @Test
    fun anEchoIsConfirmedInsideTheTransactionThatWritesIt() = runTest {
        outbox.enqueue(scope, prompt("otid-1"))
        val revision = ledger.current.revision
        val confirmed = ledger.transaction(scope) { confirmOutboxEcho(this, "otid-1", now) }
        assertTrue(confirmed)
        assertEquals(revision, ledger.current.revision)
        assertEquals(emptyList(), outbox.load(scope))
        assertEquals(false, ledger.transaction(scope) { confirmOutboxEcho(this, "otid-1", now) })
    }

    private fun stored(entries: String, version: Int = 2) =
        ledger.putEvidence("outbox/v2", """{"version":$version,"entries":[$entries]}""".encodeToByteArray())

    private fun row(otid: String, state: String = "Queued") =
        """{"otid":"$otid","content":"hi","attachments":[],"createdAt":"$SENT_AT","state":"$state",""" +
            """"attempt":0,"stateSinceMillis":1}"""

    @Test
    fun aCorruptStoredOutboxFailsInsteadOfReadingAsEmpty() = runTest {
        ledger.putEvidence("outbox/v2", "{not json".encodeToByteArray())
        assertFails { outbox.load(scope) }
        stored(row("otid-1"), version = 3)
        assertFailsWith<IllegalArgumentException> { outbox.load(scope) }
        stored(row("otid-1") + "," + row("otid-1"))
        assertFailsWith<IllegalArgumentException> { outbox.load(scope) }
        stored(row("otid-1", state = "Confirmed"))
        assertFailsWith<IllegalArgumentException> { outbox.load(scope) }
        stored(row("otid-1", state = "Failed"))
        assertFailsWith<IllegalArgumentException> { outbox.load(scope) }
        stored(row("otid-1"))
        assertEquals(listOf("otid-1"), outbox.load(scope).map { it.otid })
    }

    @Test
    fun aLegacyRecordKeepsItsAttachmentsAndAttemptThroughConversion() = runTest {
        val image = MessageContentPart.Image("aGVsbG8=", "image/png")
        legacy(legacyRecord("with-image", "Sent", SENT_AT, """{"base64":"aGVsbG8=","mediaType":"image/png"}""", attempt = 3))
        outbox.recoverAfterRestart(scope)
        val converted = OutboxStore(ledger) { now }.load(scope).single()
        assertEquals(listOf(image), converted.attachments)
        assertEquals(3, converted.attempt)
        assertEquals(OutboxState.Unknown, converted.state)
    }

    private companion object {
        const val SENT_AT = "2026-01-01T00:00:00Z"
    }
}
