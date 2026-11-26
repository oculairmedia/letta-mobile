package com.letta.mobile.data.node.carrier

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
import kotlinx.coroutines.launch

/**
 * The reference carrier: both ends in one process, joined by channels. It keeps every rule of the carrier contract
 * and nothing more, so the contract suite is proven against it, and the in-process carrier can start from it. Every
 * frame crosses as text (encoded, then decoded), so a frame that does not survive the wire fails here too.
 */
class LoopbackNodeCarrier(
    private val acceptor: NodePeerAcceptor,
    private val scope: CoroutineScope,
    private val address: NodeAddress = ADDRESS,
) : NodeCarrier {
    override suspend fun dial(address: NodeAddress): NodeLink {
        if (address != this.address) {
            throw NodeCarrierException(NodeCarrierFailure.Unreachable("no loopback node at ${address.value}"))
        }
        val wire = LoopbackWire()
        scope.launch {
            try {
                acceptor.serve(wire.nodeEnd)
            } finally {
                wire.close(NodeLinkClosure.NORMAL)
            }
        }
        return wire.shellEnd
    }

    companion object {
        /** The address a loopback carrier answers by default. */
        val ADDRESS: NodeAddress = NodeAddress("loopback:contract-node")

        /** An address no loopback carrier answers. */
        val NOWHERE: NodeAddress = NodeAddress("loopback:nowhere")

        /** The node identity the shell's end reports. */
        val NODE: NodePeerIdentity = NodePeerIdentity("loopback-node")
    }
}

/** One link: the shared closure, the control and push channels, and the call lanes opened on it. */
private class LoopbackWire {
    private val closureState = MutableStateFlow<NodeLinkClosure?>(null)
    private val toNodeControl = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toShellControl = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toShellPush = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toNodeCalls = Channel<NodeCallLane>(Channel.UNLIMITED)
    private val lanes = MutableStateFlow<List<LoopbackCall>>(emptyList())

    val closure: StateFlow<NodeLinkClosure?> = closureState.asStateFlow()

    /** Ends the link once; frames already written are still delivered, then every lane completes. */
    fun close(closure: NodeLinkClosure) {
        if (!closureState.compareAndSet(null, closure)) return
        listOf(toNodeControl, toShellControl, toShellPush).forEach { it.close() }
        toNodeCalls.close()
        lanes.value.forEach(LoopbackCall::close)
    }

    fun write(channel: Channel<NodeFrame>, frame: NodeFrame) {
        val copy = NodeFrame.decode(frame.encode())
        if (closureState.value != null || channel.trySend(copy).isFailure) throw closed()
    }

    fun closed() = NodeCarrierException(NodeCarrierFailure.Closed(closureState.value ?: NodeLinkClosure.NORMAL))

    val shellEnd: NodeLink = object : NodeLink {
        override val node: NodePeerIdentity = LoopbackNodeCarrier.NODE
        override val inbound: Flow<NodeInbound> = merge(
            toShellControl.receiveAsFlow().map { NodeInbound(NodeLane.CONTROL, it) },
            toShellPush.receiveAsFlow().map { NodeInbound(NodeLane.PUSH, it) },
        )
        override val closure: StateFlow<NodeLinkClosure?> get() = this@LoopbackWire.closure

        override suspend fun sendControl(frame: NodeFrame) = write(toNodeControl, frame)

        override suspend fun openCall(request: NodeFrame): NodeCallLane {
            if (closureState.value != null) throw closed()
            val call = LoopbackCall(this@LoopbackWire)
            lanes.update { it + call }
            call.shellEnd.send(request)
            if (toNodeCalls.trySend(call.nodeEnd).isFailure) throw closed()
            return call.shellEnd
        }

        override suspend fun close(closure: NodeLinkClosure) = this@LoopbackWire.close(closure)
    }

    val nodeEnd: NodePeerLink = object : NodePeerLink {
        override val peer: NodePeerIdentity = NodePeerIdentity.LOCAL_SHELL
        override val control: Flow<NodeFrame> = toNodeControl.receiveAsFlow()
        override val calls: Flow<NodeCallLane> = toNodeCalls.receiveAsFlow()
        override val closure: StateFlow<NodeLinkClosure?> get() = this@LoopbackWire.closure

        override suspend fun sendControl(frame: NodeFrame) = write(toShellControl, frame)

        override suspend fun push(frame: NodeFrame) = write(toShellPush, frame)

        override fun onPeerCapabilities(capabilities: Set<String>) = Unit

        override suspend fun close(closure: NodeLinkClosure) = this@LoopbackWire.close(closure)
    }
}

/** One call lane: a channel each way, each closed by its writer's finish or by the link's close. */
private class LoopbackCall(private val wire: LoopbackWire) {
    private val toNode = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toShell = Channel<NodeFrame>(Channel.UNLIMITED)

    val shellEnd: NodeCallLane = end(from = toShell, to = toNode)
    val nodeEnd: NodeCallLane = end(from = toNode, to = toShell)

    fun close() {
        toNode.close()
        toShell.close()
    }

    private fun end(from: Channel<NodeFrame>, to: Channel<NodeFrame>): NodeCallLane = object : NodeCallLane {
        override val incoming: Flow<NodeFrame> = from.receiveAsFlow()

        override suspend fun send(frame: NodeFrame) = wire.write(to, frame)

        override suspend fun finishSending() {
            to.close()
        }
    }
}
