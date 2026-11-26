package com.letta.mobile.data.node.carrier

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * How a shell reaches a node: the transport under the one client stack. There are two implementations, the
 * in-process carrier (the node in the shell's own process) and the Iroh carrier (any node, over QUIC), and one
 * contract suite runs against both. A carrier moves [NodeFrame]s on three [NodeLane]s and reports the verified
 * identity of the other end; it does not authenticate, authorize, interpret or rewrite frames. Auth, capability
 * negotiation, admin_rpc, turns and feed resume are the node protocol, spoken by [NodeSession] on the shell's side
 * and by the node on the other, identically over either carrier.
 *
 * The shell never holds an object reference into the node: everything crosses as frames. Moving the node into
 * another process later therefore changes only which carrier is used.
 */
interface NodeCarrier {
    /**
     * Reaches the node at [address] and returns the shell's end of a new link, with its control and push lanes
     * open. Throws [NodeCarrierException] with [NodeCarrierFailure.Unreachable] when the node cannot be reached or
     * the address is not one this carrier dials. Dialling sends no frame: the first frame is the session's `auth`.
     */
    suspend fun dial(address: NodeAddress): NodeLink
}

/**
 * The shell's end of one link to a node. One [NodeSession] owns it.
 *
 * Rules every implementation keeps (the contract suite checks them):
 * - Frames on one lane arrive in send order, each exactly once; frames written before a close are delivered
 *   before the close is observed, as far as the transport allows (see the contract on node-initiated closes).
 * - [inbound] is collected once. It carries [NodeLane.CONTROL] and [NodeLane.PUSH] frames and completes when the
 *   link closes, after which [closure] is set.
 * - After the link closes, [sendControl] and [openCall] throw [NodeCarrierException] with
 *   [NodeCarrierFailure.Closed], every open call lane ends, and [close] does nothing.
 * - Closing never cancels work on the node: a turn the shell started keeps running (it is detached), and the shell
 *   catches up later from the feed.
 */
interface NodeLink {
    /** The node this link reached, as the carrier verified it. */
    val node: NodePeerIdentity

    /** Control responses and node pushes, in order per lane. Collect once. */
    val inbound: Flow<NodeInbound>

    /** Null while the link is open; then why it ended. */
    val closure: StateFlow<NodeLinkClosure?>

    /** Writes [frame] on the control lane. */
    suspend fun sendControl(frame: NodeFrame)

    /**
     * Opens a new call lane and writes [request] (an `admin_rpc` frame) as its first frame. The shell then calls
     * [NodeCallLane.finishSending] for a one-shot call, or keeps the send half open to write `admin_rpc_cancel` for a
     * streamed one.
     */
    suspend fun openCall(request: NodeFrame): NodeCallLane

    /** Closes the link with [closure] (a detach for [NodeLinkClosure.NORMAL]). Idempotent. */
    suspend fun close(closure: NodeLinkClosure = NodeLinkClosure.NORMAL)
}

/**
 * One end of one call lane. On the shell's side [incoming] is the node's answer frames and [send] writes cancels;
 * on the node's side [incoming] is the request (and any cancel) and [send] writes the answer frames.
 */
interface NodeCallLane {
    /** The other end's frames on this lane, in order. Completes when the other end finishes sending or the link closes. */
    val incoming: Flow<NodeFrame>

    /** Writes one frame on this lane. Throws [NodeCarrierException] after [finishSending] or once the link closed. */
    suspend fun send(frame: NodeFrame)

    /** Ends this end's half of the lane; the other end's [incoming] completes after the frames already sent. */
    suspend fun finishSending()
}

/**
 * The node's end of one link: what the node serves for one connected peer. The Iroh carrier builds one per
 * accepted QUIC connection (first bi-stream control, second push, every later one a call); the in-process carrier
 * builds one per dial.
 */
interface NodePeerLink {
    /** The peer that dialled, as the carrier verified it. Never taken from a frame. */
    val peer: NodePeerIdentity

    /** The peer's control frames, in order. Collect once; completes when the link closes. */
    val control: Flow<NodeFrame>

    /** Each call lane the peer opens, in the order opened. Collect once; completes when the link closes. */
    val calls: Flow<NodeCallLane>

    /** Null while the link is open; then why it ended. */
    val closure: StateFlow<NodeLinkClosure?>

    /** Writes [frame] on the control lane (a response, or a node-initiated control frame such as an app tool call). */
    suspend fun sendControl(frame: NodeFrame)

    /** Writes [frame] on the push lane. */
    suspend fun push(frame: NodeFrame)

    /**
     * The node tells the link which transport capabilities the peer advertised in its accepted `auth` frame, so a
     * carrier with optional encodings may use them (Iroh splits frames into `frame_part`s only for a peer that
     * advertised it). A carrier without optional encodings ignores it.
     */
    fun onPeerCapabilities(capabilities: Set<String>)

    /** Closes the link with [closure], for example [NodeLinkClosure.AUTH_FAILURES]. Idempotent. */
    suspend fun close(closure: NodeLinkClosure = NodeLinkClosure.NORMAL)
}

/** The node side of a carrier: called once per link a peer opens, and serves it until the link closes. */
fun interface NodePeerAcceptor {
    /** Serves [link] until it closes; returning closes the link if it is still open. */
    suspend fun serve(link: NodePeerLink)
}
