package com.letta.mobile.data.plugin.wire

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException

/** One unit a transport delivers: a message's text, or the size of one too large to read. */
sealed interface LcpInboundFrame {
    data class Text(val text: String) : LcpInboundFrame

    /** A line or frame over [LcpWire.MAX_MESSAGE_BYTES], dropped unread; the peer answers it with a null-id error. */
    data class Oversized(val bytes: Long) : LcpInboundFrame
}

/**
 * Moves message texts between two peers; the framing (NDJSON lines, WebSocket text frames, an
 * in-memory pair) is the transport's. [send] suspends while the other side is not reading
 * (backpressure) and throws [LcpCallException] with [LcpErrorCode.CLOSED] once closed; [receive]
 * answers null when the other side is gone. Drivers (letta-mobile-s416w.28) supply the real ones.
 */
interface LcpTransport {
    suspend fun send(text: String)

    suspend fun receive(): LcpInboundFrame?

    fun close()
}

/**
 * Two in-memory transports joined back to back, for tests and for an in-process plugin speaking the
 * wire: each end's channel holds at most [capacity] messages, so a fast sender waits for its reader.
 */
class LoopbackLcpTransport private constructor(
    private val outbound: Channel<String>,
    private val inbound: Channel<String>,
) : LcpTransport {
    override suspend fun send(text: String) {
        try {
            outbound.send(text)
        } catch (_: ClosedSendChannelException) {
            throw LcpCallException(LcpErrorCode.CLOSED, "the transport is closed")
        }
    }

    override suspend fun receive(): LcpInboundFrame? = inbound.receiveCatching().getOrNull()?.let(LcpInboundFrame::Text)

    override fun close() {
        outbound.close()
        inbound.cancel()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 16

        /** A joined pair: what the first end sends the second receives, and the other way round. */
        fun pair(capacity: Int = DEFAULT_CAPACITY): Pair<LcpTransport, LcpTransport> {
            val aToB = Channel<String>(capacity)
            val bToA = Channel<String>(capacity)
            return LoopbackLcpTransport(aToB, bToA) to LoopbackLcpTransport(bToA, aToB)
        }
    }
}
