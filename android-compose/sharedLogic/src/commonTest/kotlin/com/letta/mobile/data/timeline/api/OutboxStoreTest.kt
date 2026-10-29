package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import com.letta.mobile.data.timeline.InMemoryTimelineStore
import com.letta.mobile.data.timeline.OutboxStore
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
    private val prompt = OutboxEntry(
        otid = "otid-1",
        content = "hello",
        attachments = listOf(IMAGE),
        createdAt = SENT_AT,
        state = OutboxState.Queued,
        attempt = 0,
        stateSinceMillis = now,
    )

    @Test
    fun admittedPromptsSurviveARestartWithTheirAttachments() = runTest {
        assertEquals(OutboxAdmission.Admitted, outbox.enqueue(scope, prompt))
        assertEquals(OutboxAdmission.AlreadyQueued, outbox.enqueue(scope, prompt))
        assertEquals(OutboxAdmission.IdentityConflict, outbox.enqueue(scope, prompt.copy(content = "other")))
        assertEquals(listOf(prompt), OutboxStore(ledger) { now }.load(scope))
    }

    @Test
    fun aFullOutboxRefusesBeforeAnythingIsWritten() = runTest {
        repeat(OutboxTransitions.BUDGET) {
            assertEquals(OutboxAdmission.Admitted, outbox.enqueue(scope, prompt.copy(otid = "otid-$it")))
        }
        val revision = ledger.current.revision
        assertEquals(OutboxAdmission.OverBudget, outbox.enqueue(scope, prompt.copy(otid = "otid-over")))
        assertEquals(revision, ledger.current.revision)
        assertEquals(OutboxTransitions.BUDGET, outbox.load(scope).size)
    }

    @Test
    fun anOversizedPromptLeavesTheOutboxUntouched() = runTest {
        outbox.enqueue(scope, prompt)
        assertFailsWith<IllegalArgumentException> {
            outbox.enqueue(scope, prompt.copy(otid = "otid-big", content = "x".repeat(OUTBOX_MAX_BYTES + 1)))
        }
        assertEquals(listOf("otid-1"), outbox.load(scope).map { it.otid })
    }

    @Test
    fun transitionsAreComparedAndSetByAttempt() = runTest {
        outbox.enqueue(scope, prompt)
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
        outbox.enqueue(scope, prompt)
        outbox.enqueue(scope, prompt.copy(otid = "otid-2"))
        outbox.transition(scope, "otid-1", OutboxEvent.EchoIngested)
        outbox.transition(scope, "otid-2", OutboxEvent.UserAbort)
        assertEquals(emptyList(), outbox.load(scope))
        assertNull(ledger.evidence["outbox/v2"])
        assertNull(outbox.transition(scope, "otid-1", OutboxEvent.DispatchStart, attempt = 0))
    }

    @Test
    fun restartTurnsAnythingThatMayHaveLeftIntoAProbe() = runTest {
        outbox.enqueue(scope, prompt.copy(otid = "queued"))
        outbox.enqueue(scope, prompt.copy(otid = "accepted"))
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
        legacy(
            CanonicalPendingLocalStore.Record("sending", "first", emptyList(), "1970-01-01T00:00:01Z"),
            CanonicalPendingLocalStore.Record("failed", "second", emptyList(), "not a time", CanonicalPendingLocalStore.Delivery.Failed),
        )
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

    @Test
    fun anEchoIsConfirmedInsideTheTransactionThatWritesIt() = runTest {
        outbox.enqueue(scope, prompt)
        val revision = ledger.current.revision
        val confirmed = ledger.transaction(scope) { confirmOutboxEcho(this, "otid-1", now) }
        assertTrue(confirmed)
        assertEquals(revision, ledger.current.revision)
        assertEquals(emptyList(), outbox.load(scope))
        assertEquals(false, ledger.transaction(scope) { confirmOutboxEcho(this, "otid-1", now) })
    }

    @Test
    fun aCorruptStoredOutboxFailsInsteadOfReadingAsEmpty() = runTest {
        "{not json".storedAsOutbox()
        assertFails { outbox.load(scope) }
        encoded(prompt).replace("\"version\":2", "\"version\":3").storedAsOutbox()
        assertFailsWith<IllegalArgumentException> { outbox.load(scope) }
        encoded(prompt, prompt).storedAsOutbox()
        assertFailsWith<IllegalArgumentException> { outbox.load(scope) }
        encoded(prompt.copy(state = OutboxState.Confirmed)).storedAsOutbox()
        assertFailsWith<IllegalArgumentException> { outbox.load(scope) }
        val failed = prompt.copy(state = OutboxState.Failed, failure = OutboxFailure.NotAccepted)
        encoded(failed).replace(",\"failure\":\"NotAccepted\"", "").storedAsOutbox()
        assertFailsWith<IllegalArgumentException> { outbox.load(scope) }
        encoded(prompt).storedAsOutbox()
        assertEquals(listOf(prompt), outbox.load(scope))
    }

    @Test
    fun aLegacyRecordKeepsItsAttachmentsAndAttemptThroughConversion() = runTest {
        legacy(
            CanonicalPendingLocalStore.Record(
                "with-image", "caption", listOf(IMAGE), SENT_AT, CanonicalPendingLocalStore.Delivery.Sent, attempt = 3,
            ),
        )
        outbox.recoverAfterRestart(scope)
        val converted = OutboxStore(ledger) { now }.load(scope).single()
        assertEquals(listOf(IMAGE), converted.attachments)
        assertEquals(3, converted.attempt)
        assertEquals(OutboxState.Unknown, converted.state)
    }

    // Stored values are written with the real serializers so a fixture cannot drift from the format.
    private fun encoded(vararg entries: OutboxEntry): String =
        """{"version":2,"entries":${json.encodeToString(ListSerializer(OutboxEntry.serializer()), entries.toList())}}"""

    private fun String.storedAsOutbox() = ledger.putEvidence("outbox/v2", encodeToByteArray())

    private fun legacy(vararg records: CanonicalPendingLocalStore.Record) {
        val list = json.encodeToString(ListSerializer(CanonicalPendingLocalStore.Record.serializer()), records.toList())
        ledger.putEvidence("pending/local/v1", """{"version":1,"records":$list}""".encodeToByteArray())
    }

    private companion object {
        const val SENT_AT = "2026-01-01T00:00:00Z"
        val IMAGE = MessageContentPart.Image("aGVsbG8=", "image/png")
        val json = Json { encodeDefaults = true }
    }
}
