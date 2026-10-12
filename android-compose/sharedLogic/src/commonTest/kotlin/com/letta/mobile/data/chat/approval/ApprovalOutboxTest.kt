package com.letta.mobile.data.chat.approval

import com.letta.mobile.data.chat.runtime.MapSettingsStore
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-bzvro.14 (F14): the offline approval outbox and its idempotency on the tool call. */
class ApprovalOutboxTest {
    private fun answer(toolCallId: String = "call-1", approve: Boolean = true) = OutboxApproval.of(
        OutboxTarget(agentId = "agent-1", conversationId = "conv-1"),
        ChatApprovalAnswer(requestId = "perm-$toolCallId", toolCallIds = listOf(toolCallId), approve = approve, reason = null),
    )

    @Test
    fun `approved while down then reconnected sends exactly one answer`() = runTest {
        val outbox = ApprovalOutbox(InMemoryApprovalOutboxStore())
        val sent = mutableListOf<String>()

        assertTrue(outbox.enqueue(answer()))
        assertFalse(outbox.enqueue(answer()), "a second tap on the same card queues nothing")
        val first = outbox.flush(stillPending = { true }) { sent += it.key; OutboxSendOutcome.Accepted }
        val second = outbox.flush(stillPending = { true }) { sent += it.key; OutboxSendOutcome.Accepted }

        assertEquals(listOf("call-1"), sent)
        assertEquals(listOf("call-1"), first.sent)
        assertEquals(OutboxFlushReport(), second)
        assertFalse(outbox.enqueue(answer()), "an answered tool call is never queued again")
    }

    @Test
    fun `a request the host no longer holds is dropped and never sent`() = runTest {
        val outbox = ApprovalOutbox(InMemoryApprovalOutboxStore())
        outbox.enqueue(answer())
        var sends = 0

        val report = outbox.flush(stillPending = { false }) { sends++; OutboxSendOutcome.Accepted }

        assertEquals(0, sends)
        assertEquals(listOf("call-1"), report.dropped)
        assertEquals(emptyList(), outbox.entries.value.entries)
    }

    @Test
    fun `a stale answer the host refuses is dropped`() = runTest {
        val outbox = ApprovalOutbox(InMemoryApprovalOutboxStore())
        outbox.enqueue(answer())

        val report = outbox.flush(stillPending = { null }) { OutboxSendOutcome.NoLongerPending }

        assertEquals(listOf("call-1"), report.dropped)
        assertEquals(emptyList(), outbox.entries.value.entries)
    }

    @Test
    fun `a send that fails offline stays queued for the next reconnect`() = runTest {
        val outbox = ApprovalOutbox(InMemoryApprovalOutboxStore())
        outbox.enqueue(answer())

        val failed = outbox.flush(stillPending = { true }) { error("link dropped") }
        val retried = outbox.flush(stillPending = { true }) { OutboxSendOutcome.Accepted }

        assertEquals(listOf("call-1"), failed.stillQueued)
        assertEquals(OutboxApprovalState.Queued, outbox.entries.value.entries.singleOrNull()?.state ?: OutboxApprovalState.Queued)
        assertEquals(listOf("call-1"), retried.sent)
    }

    @Test
    fun `the outbox survives an app restart`() = runTest {
        val settings = MapSettingsStore()
        ApprovalOutbox(SettingsApprovalOutboxStore(settings)).enqueue(answer())

        val restarted = ApprovalOutbox(SettingsApprovalOutboxStore(settings))
        val sent = mutableListOf<String>()
        restarted.flush(stillPending = { true }) { sent += it.key; OutboxSendOutcome.Accepted }

        assertEquals(listOf("call-1"), sent)
        // ...and the answered key survives too, so a third process never re-sends it.
        assertFalse(ApprovalOutbox(SettingsApprovalOutboxStore(settings)).enqueue(answer()))
    }

    @Test
    fun `overlapping reconnect signals never send one entry twice`() = runTest {
        val outbox = ApprovalOutbox(InMemoryApprovalOutboxStore())
        outbox.enqueue(answer())
        val gate = CompletableDeferred<Unit>()
        var sends = 0
        val send: suspend (OutboxApproval) -> OutboxSendOutcome = {
            sends++
            gate.await()
            OutboxSendOutcome.Accepted
        }

        val a = async { outbox.flush(stillPending = { true }, send = send) }
        val b = async { outbox.flush(stillPending = { true }, send = send) }
        testScheduler.runCurrent()
        gate.complete(Unit)
        a.await()
        b.await()

        assertEquals(1, sends)
    }

    @Test
    fun `an answer accepted outside the outbox retires its queued copy`() = runTest {
        val outbox = ApprovalOutbox(InMemoryApprovalOutboxStore())
        outbox.enqueue(answer())

        outbox.markAnswered(answer())
        var sends = 0
        outbox.flush(stillPending = { true }) { sends++; OutboxSendOutcome.Accepted }

        assertEquals(0, sends)
    }
}
