package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.node.carrier.NodeCallLane
import com.letta.mobile.data.node.carrier.NodeCarrierWire
import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.node.carrier.NodePeerAcceptor
import com.letta.mobile.data.node.carrier.NodePeerIdentity
import com.letta.mobile.data.node.carrier.NodePeerLink
import com.letta.mobile.data.node.carrier.NodePeerReassemblyBound
import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.SendStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Serves one accepted Iroh [connection] (already past the handshake, on the App Server ALPN) to [acceptor] as a
 * [NodePeerLink], the node end of the Iroh carrier, and returns when the link has closed. The streams split as the
 * node has always split them: the first bi-stream the peer opens is control, the second is push (the peer finishes
 * its send half to say it is listening), and every later one is a call lane. The link's peer is the connection's
 * verified remote endpoint id in hex. When [acceptor] returns, the link closes normally if it is still open.
 */
suspend fun serveIrohNodePeer(connection: Connection, acceptor: NodePeerAcceptor): Unit = coroutineScope {
    val link = IrohNodePeerLink(connection, IrohLinkCore(connection, this, violationsEndTheLane = true))
    link.start()
    try {
        acceptor.serve(link)
    } finally {
        withContext(NonCancellable) { link.close(NodeLinkClosure.NORMAL) }
    }
}

/**
 * The node's end of one Iroh link. The peer's control frames are delivered once both its control and push streams
 * are open, as the node has always started serving a connection; the control lane also ends when the peer finishes
 * sending on it. Writes wait until the peer has opened the lane they go on; frames go as `frame_part`s only after the
 * node reported, through [onPeerCapabilities], that the peer advertised them. How far the peer's frames may be
 * reassembled is the node's call ([boundReassembly]); until the node says, it is one Iroh frame unless the peer
 * advertised `frame_part`.
 *
 * Nothing is read ahead of the node without bound: control frames and each call lane's frames wait in a small buffer,
 * and a full buffer stops the read, so QUIC flow control holds the peer, as when the node read its streams directly.
 * Call lanes the node has not taken yet are bounded the same way: past [IrohLinkCore.LANE_BUFFER] waiting lanes the
 * node stops accepting streams until it takes one.
 */
internal class IrohNodePeerLink(
    private val connection: Connection,
    private val core: IrohLinkCore,
) : NodePeerLink, NodePeerReassemblyBound {
    private val controlFrames = Channel<NodeFrame>(IrohLinkCore.LANE_BUFFER)
    private val callLanes = Channel<NodeCallLane>(IrohLinkCore.LANE_BUFFER)
    private val controlSend = CompletableDeferred<SendStream>()
    private val pushSend = CompletableDeferred<SendStream>()
    private val controlLock = Mutex()
    private val pushLock = Mutex()

    @Volatile
    private var peerFrameParts = false

    @Volatile
    private var reassemblyBound: (() -> Int)? = null

    override val peer: NodePeerIdentity = NodePeerIdentity(IrohDiagnostics.endpointIdHex(connection.remoteId()))

    override val control: Flow<NodeFrame> = controlFrames.receiveAsFlow()

    override val calls: Flow<NodeCallLane> = callLanes.receiveAsFlow()

    override val closure: StateFlow<NodeLinkClosure?> get() = core.closure

    /** Starts accepting the peer's streams and watching the connection. */
    fun start() {
        core.whenEnded {
            controlFrames.close()
            callLanes.close()
            controlSend.completeExceptionally(core.closed())
            pushSend.completeExceptionally(core.closed())
        }
        core.watchConnection()
        core.scope.launch { acceptStreams() }
    }

    private suspend fun acceptStreams() {
        try {
            val control = connection.acceptBi()
            Telemetry.event("IrohNode", "control.accepted", "remoteEndpointId" attr peer.value)
            val push = connection.acceptBi()
            Telemetry.event("IrohNode", "stream.accepted", "remoteEndpointId" attr peer.value)
            controlSend.complete(control.send())
            pushSend.complete(push.send())
            core.read(control.recv(), controlFrames, ::maxReassembledBytes, closeAtEnd = true)
            core.scope.launch { awaitPeerListening(push) }
            while (true) {
                val stream = connection.acceptBi()
                val call = IrohCallLane(core, stream, frameParts = { peerFrameParts }, maxReassembledBytes = ::maxReassembledBytes)
                callLanes.send(call)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The connection went away (the close watcher sets the closure), or the link ended and closed the lanes.
        }
    }

    /** The peer finishes its send half of the push stream once it reads it; drain that so the stream stays healthy. */
    private suspend fun awaitPeerListening(push: BiStream) {
        runCatching { push.recv().read(1u) }
    }

    private fun maxReassembledBytes(): Int = reassemblyBound?.invoke()
        ?: if (peerFrameParts) IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES else IrohLinkCore.MAX_FRAME_BYTES

    override fun boundReassembly(maxBytes: () -> Int) {
        reassemblyBound = maxBytes
    }

    override suspend fun sendControl(frame: NodeFrame) = core.write(awaitOpen(controlSend), controlLock, frame, peerFrameParts)

    override suspend fun push(frame: NodeFrame) = core.write(awaitOpen(pushSend), pushLock, frame, peerFrameParts)

    private suspend fun awaitOpen(lane: CompletableDeferred<SendStream>): SendStream {
        if (core.closure.value != null) throw core.closed()
        return lane.await()
    }

    override fun onPeerCapabilities(capabilities: Set<String>) {
        peerFrameParts = NodeCarrierWire.FRAME_PART_CAPABILITY in capabilities
    }

    override suspend fun close(closure: NodeLinkClosure) = core.close(closure)
}
