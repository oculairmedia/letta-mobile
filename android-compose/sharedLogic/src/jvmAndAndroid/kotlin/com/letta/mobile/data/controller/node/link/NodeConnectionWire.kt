package com.letta.mobile.data.controller.node.link

/**
 * The transport under one connection the node serves: what the node's protocol code reads frames from and writes
 * frames to. It is a carrier's node end (a link of the Iroh carrier for a peer the endpoint accepted, or of the
 * in-process carrier for the shell in this process), so the node's auth, capability gates, admin router, turns and
 * feed are one code path over every carrier. Framing, `frame_part` chunking and frame limits belong to the carrier.
 *
 * Frames are wire text. A wire never interprets, rewrites or authenticates a frame, and [peerId] is the identity the
 * transport verified, never one a frame claimed.
 */
internal interface NodeConnectionWire {
    /** The peer as the transport verified it. The node keys pairing, rate limits and viewer registration by it. */
    val peerId: String

    /**
     * True when the transport verified that the peer is the shell in the node's own process. The node admits that
     * peer as an admin peer by construction, with no token.
     */
    val localShell: Boolean

    /**
     * Hands every control frame the peer sends to [onFrame], in order, until the control lane ends; throws when it
     * ended because the connection did. A wire that reassembles chunked frames bounds each logical frame, on this
     * lane and on every call lane, by [maxReassembledBytes], read at every part, and splits its writes into
     * `frame_part`s exactly when [peerFrameParts] says so: the node's answer, so the two cannot disagree.
     */
    suspend fun readControl(
        maxReassembledBytes: () -> Int,
        peerFrameParts: () -> Boolean,
        onFrame: suspend (String) -> Unit,
    )

    /** Writes [frame] on the control lane. */
    suspend fun writeControl(frame: String)

    /** Writes [frame] on the push lane. */
    suspend fun writePush(frame: String)

    /** The next call lane the peer opens; null once the link ends. */
    suspend fun nextCall(): NodeCallExchange?

    /** The transport capabilities the peer advertised in its accepted `auth` frame. */
    fun onPeerCapabilities(capabilities: Set<String>)

    /** Closes the connection with an application [code] and a fixed [reason] label. Idempotent. */
    fun close(code: Long, reason: String)

    /** Reports the connection's end (its close reason and transport statistics where the wire has them). */
    fun recordClosed()

    /** Closes the connection after the node finished serving it. */
    fun closeAfterServe()
}

/** One admin call lane as the admin server sees it: the peer's frames in, the node's frames out. */
internal interface NodeCallExchange {
    /** The peer's next frame on this lane, or null once the peer finished sending. */
    suspend fun next(): String?

    /** Writes one frame (a streamed frame or an error envelope) on this lane. */
    suspend fun write(frame: String)

    /** Writes the call's final response, already bounded by the admin server to what the peer can receive. */
    suspend fun respond(response: String)

    /** Ends the node's half of this lane. */
    suspend fun finish()
}

/**
 * A wire with nothing under it: no frames arrive and writes and closes go nowhere. It backs a node connection that
 * is driven through its handlers directly rather than served.
 */
internal class DetachedNodeWire(override val peerId: String) : NodeConnectionWire {
    override val localShell: Boolean = false

    override suspend fun readControl(
        maxReassembledBytes: () -> Int,
        peerFrameParts: () -> Boolean,
        onFrame: suspend (String) -> Unit,
    ) = Unit

    override suspend fun writeControl(frame: String) = Unit

    override suspend fun writePush(frame: String) = Unit

    override suspend fun nextCall(): NodeCallExchange? = null

    override fun onPeerCapabilities(capabilities: Set<String>) = Unit

    override fun close(code: Long, reason: String) = Unit

    override fun recordClosed() = Unit

    override fun closeAfterServe() = Unit
}
