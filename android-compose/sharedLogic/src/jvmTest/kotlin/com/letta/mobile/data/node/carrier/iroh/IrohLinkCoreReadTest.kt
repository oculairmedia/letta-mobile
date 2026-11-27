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
        // Deterministic readiness: the reader takes the lane's whole read-ahead allowance, then the frame after it
        // parks in its send on the full lane, and no read after that can happen until the node consumes. The count
        // reaching LANE_BUFFER+1 is exactly that state, so the test waits for it instead of guessing at a sleep.
        withTimeout(READ_AHEAD_TIMEOUT) { while (reads.get() <= IrohLinkCore.LANE_BUFFER) delay(POLL) }
        val read = reads.get()
        // The buffer holds LANE_BUFFER frames and one more waits in the suspended send.
        assertTrue(read <= IrohLinkCore.LANE_BUFFER + 1, "the node read $read of the peer's $PEER_FRAMES frames")
        // The parked send cannot complete while nothing takes a frame off the lane, so the count must hold still
        // however long the node refuses to read; every sample of the hold window asserts it.
        repeat(HOLD_POLLS) {
            delay(POLL)
            assertEquals(read, reads.get(), "and reads no more while the node is not consuming")
        }
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

    @Test
    fun aFramingViolationOnTheNodesEndFailsOnlyItsLane() = runBlocking {
        val nodeEnd = IrohLinkCore(Connection(NoHandle), scope, violationsEndTheLane = true)
        var sent = false
        val source: suspend (UInt) -> ByteArray = {
            if (sent) awaitCancellation()
            sent = true
            byteArrayOf(0x00, 0x20, 0x00, 0x01)
        }
        val lane = Channel<NodeFrame>(IrohLinkCore.LANE_BUFFER)
        nodeEnd.readFrom(source, lane, { IrohLinkCore.MAX_FRAME_BYTES }, closeAtEnd = true)
        val failure = withTimeout(TIMEOUT) { runCatching { lane.receive() }.exceptionOrNull() }
        assertTrue(failure is IrohFrameCodec.ProtocolException, "the lane fails with the violation: $failure")
        assertEquals(null, nodeEnd.closure.value, "the link stays up, so the node can answer on the lane")
    }

    private companion object {
        const val PEER_FRAMES = 1_000

        /** How long the peer waits for the reader to take the lane's whole read-ahead allowance. */
        val READ_AHEAD_TIMEOUT = 10.seconds

        /** The interval at which the test samples the count of frames the reader has taken off the peer. */
        val POLL = 25.milliseconds

        /** Samples in a row, while the node consumes nothing, over which the reader must stay parked. */
        const val HOLD_POLLS = 12
        val TIMEOUT = 5.seconds
    }
}
