package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import computer.iroh.Connection
import computer.iroh.NoHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class IrohLinkCoreLaneEndTest {
    @Test
    fun permanentLaneEofClosesLinkAsLost() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val core = IrohLinkCore(Connection(NoHandle), scope)
            val sink = Channel<NodeFrame>(IrohLinkCore.LANE_BUFFER)
            core.whenEnded { sink.close() }
            core.readFrom(frameThenEof(), sink, { IrohLinkCore.MAX_FRAME_BYTES }, closeAtEnd = false)

            assertEquals(NodeFrame.decode(FRAME), withTimeout(TIMEOUT) { sink.receive() })
            assertEquals(NodeLinkClosure.LOST, withTimeout(TIMEOUT) { core.closure.first { it != null } })
            assertTrueClosed(sink)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun callLaneEofClosesOnlyItsSink() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val core = IrohLinkCore(Connection(NoHandle), scope)
            val sink = Channel<NodeFrame>(IrohLinkCore.LANE_BUFFER)
            core.readFrom(frameThenEof(), sink, { IrohLinkCore.MAX_FRAME_BYTES }, closeAtEnd = true)

            assertEquals(NodeFrame.decode(FRAME), withTimeout(TIMEOUT) { sink.receive() })
            assertTrueClosed(sink)
            assertNull(core.closure.value)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun assertTrueClosed(sink: Channel<NodeFrame>) {
        assertEquals(true, withTimeout(TIMEOUT) { sink.receiveCatching().isClosed })
    }

    private fun frameThenEof(): suspend (UInt) -> ByteArray {
        var sent = false
        return {
            if (sent) byteArrayOf() else {
                sent = true
                IrohFrameCodec.encodeFrame(FRAME)
            }
        }
    }

    private companion object {
        const val FRAME = """{"type":"test"}"""
        val TIMEOUT = 2.seconds
    }
}
