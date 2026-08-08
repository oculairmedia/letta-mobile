package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.messaging.IrohAgentMessageRouter
import com.letta.mobile.util.Telemetry
import computer.iroh.Endpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * letta-mobile-bn008.3: the receive side of direct agent-to-agent messaging.
 *
 * Runs the a2a listener on the local [Endpoint]: accept an incoming connection on
 * the a2a ALPN, read the [IrohAgentMessage] envelope, ack it, then apply the
 * [IrohAgentMessageRouter] policy and hand a delivery decision to [onDeliver]
 * (which lands the message in the chosen interactive conversation and triggers a
 * turn). A dropped/duplicate/ping-pong message is still acked (delivery is
 * at-most-once on OUR side) but not delivered. NO HTTP fallback.
 *
 * [conversationsFor]/[isBusy] are injected so the routing decision is testable
 * without live state; [onDeliver] performs the actual conversation land + turn.
 */
class IrohAgentMessageReceiver(
    private val endpoint: Endpoint,
    private val router: IrohAgentMessageRouter,
    private val conversationsFor: suspend (agentId: String) -> List<IrohAgentMessageRouter.ConversationState>,
    private val onDeliver: suspend (message: IrohAgentMessage, decision: IrohAgentMessageRouter.RoutingDecision) -> Unit,
) {
    /** Start the accept loop on [scope]; returns the loop Job (cancel to stop). */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            val incoming = endpoint.acceptNext() ?: continue
            // Handle each connection concurrently so one slow peer can't block others.
            launch { handle(incoming) }
        }
    }

    private suspend fun handle(incoming: computer.iroh.Incoming) {
        runCatching {
            val conn = incoming.accept().connect()
            conn.use { c ->
                val bi = c.acceptBi()
                bi.use { stream ->
                    val wire = IrohFrameCodec.readOne(stream.recv()) ?: return
                    val message = IrohAgentMessage.decode(wire)
                    // letta-mobile-5nspp: emit a2a.recv BEFORE ack so the recv-side
                    // sensor sees the inbound even if the downstream routing/deliver
                    // path drops or rewrites it. The recv and route telemetry must
                    // pair 1:1 (one route per recv; a route without a recv is a bug).
                    Telemetry.event(
                        "A2aHost",
                        "a2a.recv",
                        "fromAgentId" to message.fromAgentId,
                        "toAgentId" to message.toAgentId,
                        "msgId" to message.msgId,
                    )
                    // Ack first (QUIC is lossless; the receiver owns at-most-once).
                    val send = stream.send()
                    IrohFrameCodec.write(send, IrohAgentMessageAck(message.msgId, accepted = true).encode())
                    send.finish()
                    // Route + deliver.
                    val candidates = conversationsFor(message.toAgentId)
                    val decision = router.route(
                        message.fromAgentId,
                        message.msgId,
                        candidates,
                        targetConversationId = message.conversationId,
                    )
                    onDeliver(message, decision)
                }
            }
        }.onFailure { e ->
            Telemetry.event(
                "A2aHost",
                "a2a.recv_failed",
                "error" to (e.message ?: e::class.simpleName ?: "unknown"),
            )
        }
    }
}
