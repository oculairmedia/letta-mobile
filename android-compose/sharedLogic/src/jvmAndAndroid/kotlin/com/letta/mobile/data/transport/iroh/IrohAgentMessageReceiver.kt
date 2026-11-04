package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.attr
import com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler
import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.util.Telemetry
import computer.iroh.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.letta.mobile.data.controller.node.iroh.AtomicJsonFile
import java.io.File
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.encodeToString
import kotlin.time.Duration.Companion.milliseconds

/**
 * The receive side of direct agent-to-agent messaging.
 *
 * Serves the a2a ALPN as a protocol of the host's own endpoint: read the [IrohAgentMessage]
 * envelope from an accepted connection, apply the [IrohAgentMessageRouter] policy, hand the
 * delivery decision to [onDeliver] (which lands the message in the chosen
 * conversation and triggers a turn), then ack the outcome. A dropped/duplicate/ping-pong
 * message is still acked (delivery is at-most-once on OUR side) but not delivered. NO HTTP
 * fallback.
 *
 * this used to run its own accept loop on a SECOND endpoint bound with
 * the host's secret key. Both endpoints registered the same node id with the relay, which keeps
 * only the newest, so relay-routed App Server dials (every browser client) reached the a2a-only
 * twin and failed TLS with `no_application_protocol`. As an [IrohNodeProtocolHandler] the
 * protocol rides the one host endpoint instead, demultiplexed by ALPN.
 *
 * [conversationsFor] is injected so the routing decision is testable without live state;
 * [onDeliver] performs the actual conversation land + turn. [authenticate] decides whether the
 * dialing node may speak for the message's claimed sender (production: [isFromIdentityIn]); a
 * message it refuses is acked as not delivered and never reaches routing.
 */
data class DeliveryOutcome(
    val delivered: Boolean,
    val reason: String? = null,
)

class IrohAgentMessageReceiver(
    private val router: IrohAgentMessageRouter,
    private val conversationsFor: suspend (agentId: String) -> List<IrohAgentMessageRouter.ConversationState>,
    private val onDeliver: suspend (message: IrohAgentMessage, decision: IrohAgentMessageRouter.RoutingDecision) -> DeliveryOutcome,
    private val authenticate: (message: IrohAgentMessage, peer: String) -> Boolean,
    private val journalFile: File? = null,
) : IrohNodeProtocolHandler {
    override val alpn: ByteArray get() = IrohAgentMessage.ALPN

    /**
     * Any peer may dial: a sender is an agent's own identity, never a paired device. Each message
     * is then authenticated against its claimed sender ([authenticate]), and the target agent is
     * gated at routing time (an unknown agent resolves no conversation).
     */
    override fun authorize(peer: String): Boolean = true

    private val routingMutex = Mutex()

    private val failures = IrohFailureLog("A2aHost", "a2a.recv_failed")

    override suspend fun accept(connection: Connection, peer: String) {
        runCatching { connection.use { receive(it, peer) } }.onFailure { e ->
            if (e is CancellationException) throw e
            failures.record(e, origin = IncomingRemote("connected", IrohFailureLog.remotePrefix(peer)))
        }
    }

    private suspend fun receive(connection: Connection, peer: String) {
        connection.acceptBi().use { stream ->
            val wire = IrohFrameCodec.readOne(stream.recv()) ?: return
            val message = IrohAgentMessage.decode(wire)
            // letta-mobile-5nspp: emit a2a.recv BEFORE ack so the recv-side
            // sensor sees the inbound even if the downstream routing/deliver
            // path drops or rewrites it. The recv and route telemetry must
            // pair 1:1 (one route per recv; a route without a recv is a bug).
            Telemetry.event(
                "A2aHost",
                "a2a.recv",
                "fromAgentId" attr message.fromAgentId,
                "toAgentId" attr message.toAgentId,
                "msgId" attr message.msgId,
                "lane" attr (message.lane ?: "inbox"),
                "correlation" attr (message.correlation?.toString() ?: ""),
            )
            Telemetry.event("A2aHost", "a2a.transport_accepted", "msgId" attr message.msgId)
            // Route + reserve before application dispatch. The ack reports the
            // observed milestone; input acceptance alone never means persisted delivery.
            val outcome = respond(message, peer)
            val applicationAck = stream.send()
            IrohFrameCodec.write(
                applicationAck,
                IrohAgentMessageAck(
                    msgId = message.msgId,
                    accepted = true,
                    applicationDelivered = outcome.delivered,
                    reason = outcome.reason,
                ).encode(),
            )
            applicationAck.finish()
        }
    }

    /** The outcome for [message] from the dialing node [peer]: refused unless it is the claimed sender. */
    suspend fun respond(message: IrohAgentMessage, peer: String): DeliveryOutcome {
        if (authenticate(message, peer)) return deliver(message)
        Telemetry.event(
            "A2aHost",
            "a2a.sender_rejected",
            "fromAgentId" attr message.fromAgentId,
            "toAgentId" attr message.toAgentId,
            "msgId" attr message.msgId,
            "peer" attr IrohFailureLog.remotePrefix(peer),
        )
        return DeliveryOutcome(false, SENDER_NOT_AUTHENTICATED)
    }

    private suspend fun deliver(message: IrohAgentMessage): DeliveryOutcome {
        val candidates = conversationsFor(message.toAgentId)
        val decision = runCatching {
            updateRouting {
                router.route(message.fromAgentId, message.msgId, candidates,
                    IrohAgentMessageRouter.InboundAddress(message.conversationId, message.lane))
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            failures.record(error, origin = IncomingRemote("journal", "local"))
            return DeliveryOutcome(false, "reservation_journal_failure")
        }
        if (decision is IrohAgentMessageRouter.RoutingDecision.Dropped) {
            return DeliveryOutcome(false, decision.reason)
        }
        val outcome = runCatching {
            withTimeout(DELIVERY_TIMEOUT_MS.milliseconds) { onDeliver(message, decision) }
        }.getOrElse { error ->
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            DeliveryOutcome(false, "delivery_timeout")
        }
        runCatching {
            updateRouting { router.settle(message.msgId, outcome.reservationState()) }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            // The durable RESERVED entry is still suppressive; never release on journal failure.
            failures.record(error, origin = IncomingRemote("journal", "local"))
        }
        return outcome
    }

    /** Marks authoritative transcript evidence without permitting another application dispatch. */
    suspend fun markPersisted(msgId: String) {
        updateRouting { router.settle(msgId, IrohAgentMessageRouter.ReservationState.PERSISTED) }
    }

    private fun DeliveryOutcome.reservationState(): IrohAgentMessageRouter.ReservationState? = when {
        delivered -> IrohAgentMessageRouter.ReservationState.PERSISTED
        reason == "application_input_accepted" -> IrohAgentMessageRouter.ReservationState.ACCEPTED
        reason in DEFINITE_FAILURES -> null
        else -> IrohAgentMessageRouter.ReservationState.AMBIGUOUS
    }

    private suspend fun <T> updateRouting(block: () -> T): T = routingMutex.withLock {
        withContext(Dispatchers.IO) {
            val file = journalFile ?: return@withContext block()
            AtomicJsonFile.withProcessLock(file.toPath()) {
                if (file.exists()) {
                    require(file.length() in 1..MAX_JOURNAL_BYTES) { "A2A receipt journal exceeds its bound" }
                    router.restore(LettaJson.persisted.decodeFromString<IrohAgentMessageRouter.Snapshot>(file.readText()))
                }
                val result = block()
                val encoded = LettaJson.persisted.encodeToString(router.snapshot())
                require(encoded.toByteArray().size <= MAX_JOURNAL_BYTES)
                AtomicJsonFile.write(file.toPath().toAbsolutePath(), encoded, requireAtomic = true)
                result
            }
        }
    }

    private companion object {
        const val MAX_JOURNAL_BYTES = 4L * 1024 * 1024
        val DEFINITE_FAILURES = setOf("application_enqueue_failure", "conversation_create_failure", "application_input_rejected")
        const val DELIVERY_TIMEOUT_MS = 10_000L
        const val SENDER_NOT_AUTHENTICATED = "sender_not_authenticated"
    }
}
