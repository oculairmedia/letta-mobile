package com.letta.mobile.data.transport.iroh

import com.letta.mobile.util.attr
import com.letta.mobile.data.controller.node.iroh.IrohNodeProtocolHandler
import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.util.Telemetry
import computer.iroh.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/**
 * The receive side of direct agent-to-agent messaging.
 *
 * Serves the a2a ALPN as a protocol of the host's own endpoint: read the [IrohAgentMessage]
 * envelope from an accepted connection, apply the [IrohAgentMessageRouter] policy, hand the
 * delivery decision to [onDeliver] (which lands the message in the chosen interactive
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
) : IrohNodeProtocolHandler {
    override val alpn: ByteArray get() = IrohAgentMessage.ALPN

    /**
     * Any peer may dial: a sender is an agent's own identity, never a paired device. Each message
     * is then authenticated against its claimed sender ([authenticate]), and the target agent is
     * gated at routing time (an unknown agent resolves no conversation).
     */
    override fun authorize(peer: String): Boolean = true

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
            )
            Telemetry.event("A2aHost", "a2a.transport_accepted", "msgId" attr message.msgId)
            // Route + deliver and return the application outcome through the
            // correlated ack only after durable app-server acceptance.
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
    internal suspend fun respond(message: IrohAgentMessage, peer: String): DeliveryOutcome {
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
        val decision = router.route(
            message.fromAgentId,
            message.msgId,
            candidates,
            targetConversationId = message.conversationId,
        )
        return runCatching {
            withTimeout(DELIVERY_TIMEOUT_MS.milliseconds) {
                onDeliver(message, decision)
            }
        }.getOrElse { error ->
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            DeliveryOutcome(false, "delivery_timeout")
        }
    }

    private companion object {
        const val DELIVERY_TIMEOUT_MS = 10_000L
        const val SENDER_NOT_AUTHENTICATED = "sender_not_authenticated"
    }
}
