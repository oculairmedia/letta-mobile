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
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The carrier for the node in the shell's own process. Each dial makes one link of channels, one per lane direction
 * plus a pair per call lane, and hands its node end to [acceptor] (the node), served in [scope] (the node's lifetime).
 * Only [NodeFrame]s cross, each as a deep snapshot of its JSON tree (a [JsonObject] may wrap a map its builder still
 * holds), without re-encoding, so nothing else the shell or the node holds is reachable from the other side. Moving the node into its own process later
 * swaps this carrier for one that serializes, with no change above it.
 *
 * The shell's end reports [node] (the node's own Iroh endpoint id, so the shell sees the same node either way); the
 * node's end reports [NodePeerIdentity.LOCAL_SHELL], which the node admits as an admin peer by construction. The
 * carrier has no optional encodings, so it ignores [NodePeerLink.onPeerCapabilities].
 *
 * Every lane is an unbounded channel: in one process there is no transport to push back, and the node's writes never
 * suspend on a slow shell. The shell keeps collecting its link's frames for the link's whole life ([NodeSession] does
 * so through its router); a carrier that serializes, when the node moves out of process, bounds its lanes instead.
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

    private val liveCallLanes = MutableStateFlow(0)

    /**
     * How many call lanes are open across this carrier's links: a lane counts from its open until both of its ends
     * finished sending, or its link closed. A long-lived link that only makes calls keeps this bounded.
     */
    internal val openCallLanes: StateFlow<Int> = liveCallLanes.asStateFlow()

    override suspend fun dial(address: NodeAddress): NodeLink {
        if (address != this.address) throw unreachable("no in-process node at ${address.value}")
        if (!scope.isActive) throw unreachable("the in-process node has stopped")
        val link = InProcessLink(node, liveCallLanes)
        // A node stopped before the serve even started still ends the link: the serve's own close never runs then.
        scope.launch { serve(link) }.invokeOnCompletion { link.close(NodeLinkClosure.LOST) }
        return link.shellEnd
    }

    /** Serves [link] once; a node that returns closes it normally, one that fails or is stopped loses it. */
    @Suppress("TooGenericExceptionCaught") // Whatever the node's serve throws loses this link; it must not escape the carrier.
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
private class InProcessLink(private val node: NodePeerIdentity, private val liveCallLanes: MutableStateFlow<Int>) {
    private val closureState = MutableStateFlow<NodeLinkClosure?>(null)
    private val toNodeControl = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toShellControl = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toShellPush = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toNodeCalls = Channel<NodeCallLane>(Channel.UNLIMITED)
    private val lanes = MutableStateFlow<Set<InProcessCall>>(emptySet())

    /** Ends the link once; frames already written are still delivered, then every lane completes. */
    fun close(closure: NodeLinkClosure) {
        if (!closureState.compareAndSet(null, closure)) return
        toNodeControl.close()
        toShellControl.close()
        toShellPush.close()
        toNodeCalls.close()
        lanes.value.forEach(InProcessCall::close)
    }

    private fun register(call: InProcessCall) {
        lanes.update { it + call }
        liveCallLanes.update { it + 1 }
    }

    /** Forgets [call] once it ended, so a link that only makes calls never accumulates them. Idempotent. */
    fun unregister(call: InProcessCall) {
        var removed = false
        lanes.update { current ->
            removed = call in current
            current - call
        }
        if (removed) liveCallLanes.update { it - 1 }
    }

    /**
     * Hands a deep snapshot of [frame] to the other end on [channel], so nothing its sender still holds is shared;
     * throws [NodeCarrierFailure.Closed] once the link or lane ended.
     */
    fun write(channel: Channel<NodeFrame>, frame: NodeFrame) {
        if (closureState.value != null || channel.trySend(NodeFrame(frame.json.snapshot())).isFailure) throw closed()
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
            // Registered first so a close that races the open still ends the lane; it unregisters itself when it ends,
            // which a failed open does at once.
            register(call)
            try {
                call.shellEnd.send(request)
                if (toNodeCalls.trySend(call.nodeEnd).isFailure) throw closed()
            } catch (failure: NodeCarrierException) {
                call.close()
                throw failure
            }
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

/**
 * One call lane: a channel each way, each closed by its writer's finish or by the link's close. The lane has ended once
 * both writers finished (or the link closed it), and then leaves its link's lanes.
 */
private class InProcessCall(private val link: InProcessLink) {
    private val toNode = Channel<NodeFrame>(Channel.UNLIMITED)
    private val toShell = Channel<NodeFrame>(Channel.UNLIMITED)
    private val finishedWriters = MutableStateFlow(0)

    val shellEnd: NodeCallLane = end(reads = toShell, writes = toNode)
    val nodeEnd: NodeCallLane = end(reads = toNode, writes = toShell)

    fun close() {
        toNode.close()
        toShell.close()
        link.unregister(this)
    }

    /** Counts a writer's first finish only; the lane ends with the second. */
    private fun finished(writes: Channel<NodeFrame>) {
        if (writes.close() && finishedWriters.updateAndGet { it + 1 } == WRITERS) link.unregister(this)
    }

    private fun end(reads: Channel<NodeFrame>, writes: Channel<NodeFrame>): NodeCallLane = object : NodeCallLane {
        override val incoming: Flow<NodeFrame> = reads.receiveAsFlow()

        override suspend fun send(frame: NodeFrame) = link.write(writes, frame)

        override suspend fun finishSending() = finished(writes)
    }
}

/** A copy of this tree that shares no map or list with it; primitives are immutable and are shared. */
private fun JsonElement.snapshot(): JsonElement = when (this) {
    is JsonObject -> snapshot()
    is JsonArray -> JsonArray(map { it.snapshot() })
    else -> this
}

private fun JsonObject.snapshot(): JsonObject = JsonObject(entries.associate { (key, value) -> key to value.snapshot() })

/** A call lane's two writers: the shell's end and the node's. */
private const val WRITERS = 2
