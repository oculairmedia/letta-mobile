package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.link.NodeCallExchange
import com.letta.mobile.data.controller.node.link.NodeCallFraming
import com.letta.mobile.data.controller.node.link.NodeConnectionWire
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.SendStream

/**
 * One accepted Iroh [connection] as the node's wire: the first bi-stream the peer opens is the control lane, the
 * second the push lane (the peer's readiness byte arrives on its receive half), and every later one an admin call.
 * Frames are length-prefixed by [IrohFrameCodec] and split into `frame_part`s only where the node allows it.
 * [peer] carries the endpoint id the QUIC handshake proved.
 */
internal fun irohConnectionWire(connection: Connection, peer: IrohConnectionAuthentication): NodeConnectionWire =
    object : NodeConnectionWire {
        @Volatile
        private var control: BiStream? = null

        @Volatile
        private var controlSend: SendStream? = null

        @Volatile
        private var push: BiStream? = null

        @Volatile
        private var pushSend: SendStream? = null

        @Volatile
        private var pushSink: ViewerFrameSink? = null

        override val peerId: String = peer.remoteEndpointId

        override val localShell: Boolean = false

        override suspend fun openLanes() {
            val controlBiStream = connection.acceptBi()
            Telemetry.event("IrohNode", "control.accepted", "remoteEndpointId" attr peerId)
            val streamBiStream = connection.acceptBi()
            Telemetry.event("IrohNode", "stream.accepted", "remoteEndpointId" attr peerId)
            val streamSend = streamBiStream.send()
            control = controlBiStream
            controlSend = controlBiStream.send()
            push = streamBiStream
            pushSend = streamSend
            pushSink = sendStreamSink(streamSend)
        }

        override suspend fun readControl(maxReassembledBytes: () -> Int, onFrame: suspend (String) -> Unit) {
            IrohFrameCodec.readAll(
                recvStream = checkNotNull(control).recv(),
                maxFrameBytes = IrohNodeConnection.MAX_FRAME_BYTES,
                maxReassembledBytesProvider = maxReassembledBytes,
            ) { frameJson -> onFrame(frameJson) }
        }

        override suspend fun writeControl(frame: String, frameParts: Boolean) {
            IrohFrameCodec.write(
                checkNotNull(controlSend),
                frame,
                IrohNodeConnection.MAX_FRAME_BYTES,
                allowFrameParts = frameParts,
            )
        }

        override suspend fun writePush(frame: String, frameParts: Boolean, maxFrameBytes: Int) {
            checkNotNull(pushSink).writeFrame(frame, frameParts, maxFrameBytes)
        }

        override suspend fun awaitPushReadiness() {
            val recvStream = checkNotNull(push).recv()
            runCatching { recvStream.read(1u) }
        }

        override suspend fun finishControl() {
            controlSend?.finish()
        }

        override suspend fun finishPush() {
            pushSend?.finish()
        }

        override suspend fun nextCall(framing: NodeCallFraming): NodeCallExchange {
            val biStream = connection.acceptBi()
            val recvStream = biStream.recv()
            val sendStream = biStream.send()
            return framing.frame(
                read = { maxBytes -> recvStream.read(maxBytes) },
                writeAll = { bytes -> sendStream.writeAll(bytes) },
                finish = { sendStream.finish() },
            )
        }

        override fun onPeerCapabilities(capabilities: Set<String>) = Unit

        override fun close(code: Long, reason: String) {
            connection.close(code, reason.encodeToByteArray())
        }

        override fun recordClosed() {
            recordConnectionClosed("remoteEndpointId" attr peerId, connection)
        }

        override fun closeAfterServe() {
            connection.close()
        }
    }
