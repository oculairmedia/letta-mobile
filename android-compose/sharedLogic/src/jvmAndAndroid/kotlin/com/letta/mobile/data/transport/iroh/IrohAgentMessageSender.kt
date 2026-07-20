package com.letta.mobile.data.transport.iroh

import computer.iroh.Endpoint
import kotlinx.coroutines.withTimeout

import kotlin.time.Duration.Companion.milliseconds
/** Typed outcome of a direct agent-to-agent send. Never throws to the caller. */
sealed interface AgentSendResult {
    /** The peer acked receipt of [msgId]. */
    data class Delivered(val msgId: String) : AgentSendResult
    /** The target could not be resolved to a dialable address. */
    data class Unaddressable(val toAgentId: String, val reason: String) : AgentSendResult
    /** Dial/stream/ack failed. */
    data class Failed(val toAgentId: String, val reason: String) : AgentSendResult
}

/**
 * letta-mobile-bn008.2: direct P2P agent-to-agent SEND over Iroh.
 *
 * Resolves the target agent's dialable address via the bn008.1 resolver, dials it
 * on the a2a ALPN, opens a QUIC BiStream, writes the [IrohAgentMessage] envelope,
 * and reads a single ack. QUIC guarantees reliable/ordered delivery — the ack
 * confirms the peer received it; no gap-replay is needed. NO HTTP fallback. Only
 * the SEND path lives here (inbound routing is bn008.3).
 */
class IrohAgentMessageSender(
    private val endpointProvider: () -> Endpoint,
    private val resolver: IrohAgentAddressResolver,
    private val connectTimeoutMs: Long = 15_000,
    private val ackTimeoutMs: Long = 15_000,
) {
    constructor(
        endpoint: Endpoint,
        resolver: IrohAgentAddressResolver,
        connectTimeoutMs: Long = 15_000,
        ackTimeoutMs: Long = 15_000,
    ) : this({ endpoint }, resolver, connectTimeoutMs, ackTimeoutMs)

    suspend fun send(message: IrohAgentMessage): AgentSendResult {
        val address = when (val resolution = resolver.resolve(message.toAgentId)) {
            is AddressResolution.Found -> resolution.address
            is AddressResolution.Unavailable ->
                return AgentSendResult.Unaddressable(message.toAgentId, resolution.reason)
        }
        return runCatching {
            val connection = withTimeout(connectTimeoutMs.milliseconds) {
                endpointProvider().connect(address.toEndpointAddr(), IrohAgentMessage.ALPN)
            }
            connection.use { conn ->
                val bi = conn.openBi()
                bi.use { stream ->
                    val sendStream = stream.send()
                    IrohFrameCodec.write(sendStream, message.encode())
                    sendStream.finish()
                    val ackWire = withTimeout(ackTimeoutMs.milliseconds) {
                        IrohFrameCodec.readOne(stream.recv())
                    } ?: return@runCatching AgentSendResult.Failed(message.toAgentId, "no_ack")
                    val ack = IrohAgentMessageAck.decode(ackWire)
                    if (ack.accepted && ack.msgId == message.msgId) {
                        AgentSendResult.Delivered(message.msgId)
                    } else {
                        AgentSendResult.Failed(message.toAgentId, "ack_rejected_or_mismatched")
                    }
                }
            }
        }.getOrElse { t ->
            AgentSendResult.Failed(message.toAgentId, t.message ?: t::class.simpleName ?: "send_error")
        }
    }
}
