package com.letta.mobile.data.node.carrier.iroh

import com.letta.mobile.data.node.carrier.NodeCallLane
import com.letta.mobile.data.node.carrier.NodeCarrierWire
import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.node.carrier.NodePeerAcceptor
import com.letta.mobile.data.node.carrier.NodePeerIdentity
import com.letta.mobile.data.node.carrier.NodePeerLink
import com.letta.mobile.data.transport.iroh.IrohDiagnostics
import com.letta.mobile.data.transport.iroh.IrohFrameCodec
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
 * node's own connection handler splits them: the first bi-stream the peer opens is control, the second is push (the
 * peer finishes its send half to say it is listening), and every later one is a call lane. The link's peer is the
 * connection's verified remote endpoint id in hex. When [acceptor] returns, the link closes normally if it is still
 * open.
 */
suspend fun serveIrohNodePeer(connection: Connection, acceptor: NodePeerAcceptor): Unit = coroutineScope {
    val link = IrohNodePeerLink(connection, IrohLinkCore(connection, this))
    link.start()
    try {
        acceptor.serve(link)
    } finally {
        withContext(NonCancellable) { link.close(NodeLinkClosure.NORMAL) }
    }
}

/**
 * The node's end of one Iroh link. Writes wait until the peer has opened the lane they go on; frames go as
 * `frame_part`s only after the node reported, through [onPeerCapabilities], that the peer advertised them, and the
 * peer's frames may be reassembled past one Iroh frame only then (until it authenticates, a peer cannot make the node
 * hold more than one frame).
 */
internal class IrohNodePeerLink(
    private val connection: Connection,
    private val core: IrohLinkCore,
) : NodePeerLink {
    private val controlFrames = Channel<NodeFrame>(Channel.UNLIMITED)
    private val callLanes = Channel<NodeCallLane>(Channel.UNLIMITED)
    private val controlSend = CompletableDeferred<SendStream>()
    private val pushSend = CompletableDeferred<SendStream>()
    private val controlLock = Mutex()
    private val pushLock = Mutex()

    @Volatile
    private var peerFrameParts = false

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
            core.read(control.recv(), controlFrames, ::maxReassembledBytes, closeAtEnd = false)
            controlSend.complete(control.send())
            val push = connection.acceptBi()
            pushSend.complete(push.send())
            core.scope.launch { awaitPeerListening(push) }
            while (true) {
                val stream = connection.acceptBi()
                val call = IrohCallLane(core, stream, frameParts = { peerFrameParts }, maxReassembledBytes = ::maxReassembledBytes)
                if (callLanes.trySend(call).isFailure) return
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The connection went away; the close watcher sets the closure.
        }
    }

    /** The peer finishes its send half of the push stream once it reads it; drain that so the stream stays healthy. */
    private suspend fun awaitPeerListening(push: BiStream) {
        runCatching { push.recv().read(1u) }
    }

    private fun maxReassembledBytes(): Int =
        if (peerFrameParts) IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES else IrohLinkCore.MAX_FRAME_BYTES

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
