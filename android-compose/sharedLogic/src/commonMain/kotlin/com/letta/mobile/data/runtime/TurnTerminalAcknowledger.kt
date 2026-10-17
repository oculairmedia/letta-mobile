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
    private val lock = SynchronizedObject()
    private val acknowledged = LinkedHashSet<TerminalDelivery>()

    /** Acks [received] when it is a `turn_finished` kept for this client and not yet acked on its generation. */
    suspend fun acknowledge(received: AppServerReceivedFrame) {
        val frame = received.frame as? AppServerInboundFrame.TurnFinished ?: return
        val consumer = consumerId?.takeIf { it.value in frame.terminalConsumerIds } ?: return
        val delivery = TerminalDelivery(frame.idempotencyKey, received.connectionGeneration ?: connectionGenerationProvider())
        if (!claim(delivery)) return
        val outcome = try {
            send(AppServerCommand.TurnFinishedAck.of(frame, consumer))
        } catch (cancellation: CancellationException) {
            release(delivery)
            throw cancellation
        }
        if (outcome != SENT) release(delivery)
        Telemetry.event(
            TELEMETRY_TAG, "terminal.acknowledged",
            "conversationId" to frame.runtime.conversationId,
            "turnId" to frame.turnId,
            "generation" to delivery.generation,
            "outcome" to outcome,
            level = if (outcome == SENT) Telemetry.Level.INFO else Telemetry.Level.WARN,
        )
    }

    /** Never throws: an unsent ack only means the server replays the terminal on the next connection. */
    private suspend fun send(ack: AppServerCommand.TurnFinishedAck): String = try {
        withTimeoutOrNull(SEND_TIMEOUT_MS.milliseconds) {
            client.acknowledgeTurnFinished(ack)
            SENT
        } ?: "timeout"
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        "failed=${error::class.simpleName}"
    }

    private fun claim(delivery: TerminalDelivery): Boolean = synchronized(lock) {
        if (!acknowledged.add(delivery)) return@synchronized false
        if (acknowledged.size > CAPACITY) acknowledged.remove(acknowledged.first())
        true
    }

    private fun release(delivery: TerminalDelivery) = synchronized(lock) { acknowledged.remove(delivery) }

    private companion object {
        const val TELEMETRY_TAG = "AppServerTurnEngine"
        const val SENT = "sent"

        /** The ack runs on a turn's NonCancellable tail, so a stalled socket must not hold it. */
        const val SEND_TIMEOUT_MS = 5_000L

        /** Bounded: a replay older than this many acks is simply acked again. */
        const val CAPACITY = 256
    }
}
