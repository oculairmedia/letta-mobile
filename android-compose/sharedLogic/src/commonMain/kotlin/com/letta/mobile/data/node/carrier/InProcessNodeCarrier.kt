package com.letta.mobile.data.node.carrier

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The carrier for the node in the shell's own process. Each dial makes one link of channels, one per lane direction
 * plus a pair per call lane, and hands its node end to [acceptor] (the node), served in [scope] (the node's lifetime).
 * Only [NodeFrame]s cross: a frame is an immutable JSON object, so it passes whole without re-encoding, and nothing
 * else the shell or the node holds is reachable from the other side. Moving the node into its own process later
 * swaps this carrier for one that serializes, with no change above it.
 *
 * The shell's end reports [node] (the node's own Iroh endpoint id, so the shell sees the same node either way); the
 * node's end reports [NodePeerIdentity.LOCAL_SHELL], which the node admits as an admin peer by construction. The
 * carrier has no optional encodings, so it ignores [NodePeerLink.onPeerCapabilities].
 *
 * @param name the node's in-process name; the carrier answers only [address], `in-process:<name>`.
 */
class InProcessNodeCarrier(
    name: String,
    private val node: NodePeerIdentity,
    private val acceptor: NodePeerAcceptor,
    private val scope: CoroutineScope,
) : NodeCarrier {
    /** The one address this carrier dials. */
    val address: NodeAddress = NodeAddress(SCHEME + name)

    override suspend fun dial(address: NodeAddress): NodeLink {
        if (address != this.address) throw unreachable("no in-process node at ${address.value}")
        if (!scope.isActive) throw unreachable("the in-process node has stopped")
        val link = InProcessLink(node)
        scope.launch { serve(link) }
        return link.shellEnd
    }

    /** Serves [link] once; a node that returns closes it normally, one that fails or is stopped loses it. */
    private suspend fun serve(link: InProcessLink) {
        var closure = NodeLinkClosure.NORMAL
        try {
            acceptor.serve(link.nodeEnd)
        } catch (cancelled: CancellationException) {
            closure = NodeLinkClosure.LOST
            throw cancelled
        } catch (failure: Exception) {
            closure = NodeLinkClosure.LOST
            Telemetry.event(
                "InProcessCarrier", "serve.failed",
                "class" attr failure::class.simpleName,
                level = Telemetry.Level.WARN,
            )
        } finally {
            link.close(closure)
        }
    }

    private fun unreachable(detail: String) = NodeCarrierException(NodeCarrierFailure.Unreachable(detail))

    companion object {
        /** The address prefix this carrier dials. */
        const val SCHEME: String = "in-process:"
    }
}

/** One in-process link: the shared closure, the control and push channels, and the call lanes opened on it. */
private class InProcessLink(private val node: NodePeerIdentity) {
    private val closureState = MutableStateFlow<NodeLinkClosure?>(null)
    private val toNodeControl = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toShellControl = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toShellPush = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toNodeCalls = Channel<NodeCallLane>(Channel.UNLIMITED)
    private val lanes = MutableStateFlow<List<InProcessCall>>(emptyList())

    /** Ends the link once; frames already written are still delivered, then every lane completes. */
    fun close(closure: NodeLinkClosure) {
        if (!closureState.compareAndSet(null, closure)) return
        toNodeControl.close()
        toShellControl.close()
        toShellPush.close()
        toNodeCalls.close()
        lanes.value.forEach(InProcessCall::close)
    }

    /** Hands [frame] to the other end on [channel]; throws [NodeCarrierFailure.Closed] once the link or lane ended. */
    fun write(channel: Channel<NodeFrame>, frame: NodeFrame) {
        if (closureState.value != null || channel.trySend(frame).isFailure) throw closed()
    }

    private fun closed() = NodeCarrierException(NodeCarrierFailure.Closed(closureState.value ?: NodeLinkClosure.NORMAL))

    val shellEnd: NodeLink = object : NodeLink {
        override val node: NodePeerIdentity = this@InProcessLink.node
        override val inbound: Flow<NodeInbound> = merge(
            toShellControl.receiveAsFlow().map { NodeInbound(NodeLane.CONTROL, it) },
            toShellPush.receiveAsFlow().map { NodeInbound(NodeLane.PUSH, it) },
        )
        override val closure: StateFlow<NodeLinkClosure?> = closureState.asStateFlow()

        override suspend fun sendControl(frame: NodeFrame) = write(toNodeControl, frame)

        override suspend fun openCall(request: NodeFrame): NodeCallLane {
            if (closureState.value != null) throw closed()
            val call = InProcessCall(this@InProcessLink)
            lanes.update { it + call }
            call.shellEnd.send(request)
            if (toNodeCalls.trySend(call.nodeEnd).isFailure) throw closed()
            return call.shellEnd
        }

        override suspend fun close(closure: NodeLinkClosure) = this@InProcessLink.close(closure)
    }

    val nodeEnd: NodePeerLink = object : NodePeerLink {
        override val peer: NodePeerIdentity = NodePeerIdentity.LOCAL_SHELL
        override val control: Flow<NodeFrame> = toNodeControl.receiveAsFlow()
        override val calls: Flow<NodeCallLane> = toNodeCalls.receiveAsFlow()
        override val closure: StateFlow<NodeLinkClosure?> = closureState.asStateFlow()

        override suspend fun sendControl(frame: NodeFrame) = write(toShellControl, frame)

        override suspend fun push(frame: NodeFrame) = write(toShellPush, frame)

        override fun onPeerCapabilities(capabilities: Set<String>) = Unit

        override suspend fun close(closure: NodeLinkClosure) = this@InProcessLink.close(closure)
    }
}

/** One call lane: a channel each way, each closed by its writer's finish or by the link's close. */
private class InProcessCall(private val link: InProcessLink) {
    private val toNode = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toShell = Channel<NodeFrame>(Channel.UNLIMITED)

    val shellEnd: NodeCallLane = end(reads = toShell, writes = toNode)
    val nodeEnd: NodeCallLane = end(reads = toNode, writes = toShell)

    fun close() {
        toNode.close()
        toShell.close()
    }

    private fun end(reads: Channel<NodeFrame>, writes: Channel<NodeFrame>): NodeCallLane = object : NodeCallLane {
        override val incoming: Flow<NodeFrame> = reads.receiveAsFlow()

        override suspend fun send(frame: NodeFrame) = link.write(writes, frame)

        override suspend fun finishSending() {
            writes.close()
        }
    }
}
