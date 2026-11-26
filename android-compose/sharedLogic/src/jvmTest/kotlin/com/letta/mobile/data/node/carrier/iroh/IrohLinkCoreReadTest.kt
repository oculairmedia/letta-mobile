package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import computer.iroh.Connection
import computer.iroh.NoHandle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * How an Iroh link reads a peer's stream, over a fake byte source (no network, no QUIC): what it reads ahead of a
 * node that is not consuming is bounded, a frame that is not a JSON object ends the link, and a framing violation on
 * the shell's end loses the link.
 */
class IrohLinkCoreReadTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val core = IrohLinkCore(Connection(NoHandle), scope)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun anUnauthenticatedPeerCannotMakeTheNodeReadAheadPastTheLaneBuffer() = runBlocking {
        val reads = AtomicInteger()
        val frame = IrohFrameCodec.encodeFrame("""{"type":"admin_rpc","request_id":"r","method":"health.check"}""")
        // A peer that never stops sending, one frame per read; the node never takes a frame off the lane.
        val source: suspend (UInt) -> ByteArray = {
            if (reads.incrementAndGet() > PEER_FRAMES) awaitCancellation()
            frame
        }
        core.readFrom(source, Channel(IrohLinkCore.LANE_BUFFER), { IrohLinkCore.MAX_FRAME_BYTES }, closeAtEnd = true)
        delay(SETTLE)
        val read = reads.get()
        // The buffer holds LANE_BUFFER frames and one more waits in the suspended send.
        assertTrue(read <= IrohLinkCore.LANE_BUFFER + 1, "the node read $read of the peer's $PEER_FRAMES frames")
        delay(SETTLE)
        assertEquals(read, reads.get(), "and reads no more while the node is not consuming")
    }

    @Test
    fun aFrameThatIsNotAJsonObjectEndsTheLink() = runBlocking {
        var sent = false
        val source: suspend (UInt) -> ByteArray = {
            if (sent) awaitCancellation()
            sent = true
            IrohFrameCodec.encodeFrame("not a json object")
        }
        core.readFrom(source, Channel(IrohLinkCore.LANE_BUFFER), { IrohLinkCore.MAX_FRAME_BYTES }, closeAtEnd = false)
        assertEquals(IrohLinkCore.MALFORMED_FRAME, withTimeout(TIMEOUT) { core.closure.first { it != null } })
    }

    @Test
    fun aFramingViolationOnTheShellsEndLosesTheLink() = runBlocking {
        var sent = false
        // A length prefix past one frame, from a peer that may not send frame_parts here.
        val oversized = byteArrayOf(0x00, 0x20, 0x00, 0x01)
        val source: suspend (UInt) -> ByteArray = {
            if (sent) awaitCancellation()
            sent = true
            oversized
        }
        val sink = Channel<NodeFrame>(IrohLinkCore.LANE_BUFFER)
        core.readFrom(source, sink, { IrohLinkCore.MAX_FRAME_BYTES }, closeAtEnd = false)
        assertEquals(NodeLinkClosure.LOST, withTimeout(TIMEOUT) { core.closure.first { it != null } })
    }

    private companion object {
        const val PEER_FRAMES = 1_000
        val SETTLE = 300.milliseconds
        val TIMEOUT = 5.seconds
    }
}
