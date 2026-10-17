package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerTerminalConsumerId
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** One delivery of a durable terminal: its frame key on one connection generation. */
private data class TerminalDelivery(val idempotencyKey: String, val generation: Long)

/** The ack one delivery calls for. */
private class PendingAck(
    val frame: AppServerInboundFrame.TurnFinished,
    val consumer: AppServerTerminalConsumerId,
    val delivery: TerminalDelivery,
)

/** The deliveries already acked, bounded: one older than [capacity] acks is simply acked again. */
private class AckLedger(private val capacity: Int) {
    private val lock = SynchronizedObject()
    private val acknowledged = LinkedHashSet<TerminalDelivery>()

    /** True when [delivery] was not acked yet; it is then recorded. */
    fun claim(delivery: TerminalDelivery): Boolean = synchronized(lock) {
        val added = acknowledged.add(delivery)
        if (acknowledged.size > capacity) acknowledged.remove(acknowledged.first())
        added
    }

    fun release(delivery: TerminalDelivery) {
        synchronized(lock) { acknowledged.remove(delivery) }
    }
}

/**
 * letta-mobile-zxk2n.2: answers every `turn_finished` that lists this client's
 * [AppServerTerminalConsumerId] with `turn_finished_ack`, so the server stops replaying it.
 *
 * One ack per delivery: the same frame seen twice on one connection (by the turn that applied it
 * and by the between-turns observer) is acked once; the server's replay on a later connection
 * generation is acked again, because the server only replays what it has not seen acked. Applying
 * the terminal is the turn engine's job and is deduplicated there ([TurnBoundaryGate]); this only
 * sends the receipt. A frame that names no consumer of ours (an older server, another client) is
 * never acked, so the ack is gated on the host the same way the consumer id is.
 */
internal class TurnTerminalAcknowledger(
    private val client: AppServerClient,
    private val consumerId: AppServerTerminalConsumerId?,
    private val connectionGenerationProvider: () -> Long,
) {
    private val ledger = AckLedger(CAPACITY)

    /** Acks [received] when it is a `turn_finished` kept for this client and not yet acked on its generation. */
    suspend fun acknowledge(received: AppServerReceivedFrame) {
        val pending = pendingAckFor(received) ?: return
        if (!ledger.claim(pending.delivery)) return
        report(pending, sendClaimed(pending))
    }

    /** The ack [received] calls for: a `turn_finished` that lists this client's consumer id; null otherwise. */
    private fun pendingAckFor(received: AppServerReceivedFrame): PendingAck? {
        val frame = received.frame as? AppServerInboundFrame.TurnFinished ?: return null
        val consumer = consumerId?.takeIf { it.value in frame.terminalConsumerIds } ?: return null
        return PendingAck(frame, consumer, deliveryOf(frame, received.connectionGeneration))
    }

    /** One delivery per frame key and connection generation; an unstamped frame is on the live generation. */
    private fun deliveryOf(frame: AppServerInboundFrame.TurnFinished, stamped: Long?): TerminalDelivery =
        TerminalDelivery(frame.idempotencyKey, stamped ?: connectionGenerationProvider())

    /** Sends a claimed ack; a cancelled or unsent ack gives its claim back, so a later delivery retries it. */
    private suspend fun sendClaimed(pending: PendingAck): String {
        val outcome = try {
            send(AppServerCommand.TurnFinishedAck.of(pending.frame, pending.consumer))
        } catch (cancellation: CancellationException) {
            ledger.release(pending.delivery)
            throw cancellation
        }
        if (outcome != SENT) ledger.release(pending.delivery)
        return outcome
    }

    /** Never throws: an unsent ack only means the server replays the terminal on the next connection. */
    private suspend fun send(ack: AppServerCommand.TurnFinishedAck): String = outcomeOf(
        runCatching { withTimeoutOrNull(SEND_TIMEOUT_MS.milliseconds) { client.acknowledgeTurnFinished(ack) } },
    )

    /** `sent`, `timeout` (null result) or `failed=<type>`; a cancellation is rethrown. */
    private fun outcomeOf(sent: Result<Unit?>): String {
        val error = sent.exceptionOrNull() ?: return if (sent.getOrNull() == null) TIMEOUT else SENT
        if (error is CancellationException) throw error
        return "failed=${error::class.simpleName}"
    }

    private fun report(pending: PendingAck, outcome: String) {
        Telemetry.event(
            TELEMETRY_TAG, "terminal.acknowledged",
            "conversationId" to pending.frame.runtime.conversationId,
            "turnId" to pending.frame.turnId,
            "generation" to pending.delivery.generation,
            "outcome" to outcome,
            level = if (outcome == SENT) Telemetry.Level.INFO else Telemetry.Level.WARN,
        )
    }

    private companion object {
        const val TELEMETRY_TAG = "AppServerTurnEngine"
        const val SENT = "sent"
        const val TIMEOUT = "timeout"

        /** The ack runs on a turn's NonCancellable tail, so a stalled socket must not hold it. */
        const val SEND_TIMEOUT_MS = 5_000L

        /** Bounded: a replay older than this many acks is simply acked again. */
        const val CAPACITY = 256
    }
}
