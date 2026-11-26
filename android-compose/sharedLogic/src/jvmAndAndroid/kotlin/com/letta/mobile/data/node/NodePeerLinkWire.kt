package com.letta.mobile.data.node

import com.letta.mobile.data.controller.node.link.NodeCallExchange
import com.letta.mobile.data.controller.node.link.NodeCallFraming
import com.letta.mobile.data.controller.node.link.NodeConnectionWire
import com.letta.mobile.data.node.carrier.NodeCallLane
import com.letta.mobile.data.node.carrier.NodeFrame
import com.letta.mobile.data.node.carrier.NodeLinkClosure
import com.letta.mobile.data.node.carrier.NodePeerLink
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * A carrier's node end ([link]) as the node's wire. The carrier already moves whole frames on its three lanes, so this
 * only converts between frames and the wire text the node's protocol code reads and writes: no chunking, no frame
 * limits (those belong to a carrier that serializes), and no readiness handshake. Closes run in [scope], the scope the
 * link is served in, because the node closes from places that cannot suspend.
 *
 * [localShell] is how the link was created, never what it says about itself: only the node's own in-process carrier
 * passes true. A link whose peer merely reads [com.letta.mobile.data.node.carrier.NodePeerIdentity.LOCAL_SHELL]
 * through any other acceptor meets the endpoint's policy like every other peer.
 */
internal class NodePeerLinkWire(
    private val link: NodePeerLink,
    private val scope: CoroutineScope,
    override val localShell: Boolean = false,
) : NodeConnectionWire {
    override val peerId: String = link.peer.value

    override suspend fun openLanes() = Unit

    /** Frames already cross whole in process, so [maxReassembledBytes] does not apply; a serializing carrier must apply it. */
    override suspend fun readControl(maxReassembledBytes: () -> Int, onFrame: suspend (String) -> Unit) {
        link.control.collect { frame -> onFrame(frame.encode()) }
    }

    override suspend fun writeControl(frame: String, frameParts: Boolean) = link.sendControl(NodeFrame.decode(frame))

    override suspend fun writePush(frame: String, frameParts: Boolean, maxFrameBytes: Int) = link.push(NodeFrame.decode(frame))

    override suspend fun awaitPushReadiness() = Unit

    override suspend fun finishControl() = Unit

    override suspend fun finishPush() = Unit

    override suspend fun nextCall(framing: NodeCallFraming): NodeCallExchange? = link.calls.firstOrNull()?.let(::exchangeOf)

    override fun onPeerCapabilities(capabilities: Set<String>) = link.onPeerCapabilities(capabilities)

    override fun close(code: Long, reason: String) = closeWith(NodeLinkClosure(code, reason))

    override fun recordClosed() {
        Telemetry.event(
            "IrohNode", "connection.closed",
            "remoteEndpointId" attr peerId,
            "closeReason" attr (link.closure.value?.reason ?: "open"),
        )
    }

    /**
     * The node stopped serving a link the peer had not closed (a peer's own detach has already closed it, making this a
     * no-op). To the shell that is the node going away, not its own detach, so the closure is [NodeLinkClosure.LOST].
     */
    override fun closeAfterServe() = closeWith(NodeLinkClosure.LOST)

    /** Starts the close at once, even from a cancelled scope; a link that already ended ignores it. */
    private fun closeWith(closure: NodeLinkClosure) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { link.close(closure) }
    }

    /** One call lane: its frames already arrive whole, and the response needs no chunking. */
    private fun exchangeOf(lane: NodeCallLane): NodeCallExchange = object : NodeCallExchange {
        override suspend fun next(): String? = lane.incoming.firstOrNull()?.encode()

        override suspend fun write(frame: String) = lane.send(NodeFrame.decode(frame))

        override suspend fun respond(response: String) = write(response)

        override suspend fun finish() = lane.finishSending()
    }
}
