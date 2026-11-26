package com.letta.mobile.data.controller.node.link

/**
 * The transport under one connection the node serves: what the node's protocol code reads frames from and writes
 * frames to. Two implementations exist: an accepted Iroh QUIC connection (its streams and its length-prefixed frame
 * codec), and a carrier's node end (the in-process carrier's link, whose frames cross whole). The node's auth,
 * capability gates, admin router, turns and feed are the same code over either; only this seam differs.
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

    /** Waits until the peer has opened its control and push lanes. */
    suspend fun openLanes()

    /**
     * Hands every control frame the peer sends to [onFrame], in order, until the control lane ends. A wire that
     * reassembles chunked frames bounds each logical frame by [maxReassembledBytes], read at every part.
     */
    suspend fun readControl(maxReassembledBytes: () -> Int, onFrame: suspend (String) -> Unit)

    /** Writes [frame] on the control lane; [frameParts] allows a wire with optional chunking to split it. */
    suspend fun writeControl(frame: String, frameParts: Boolean)

    /** Writes [frame] on the push lane; [frameParts] and [maxFrameBytes] govern a wire that chunks frames. */
    suspend fun writePush(frame: String, frameParts: Boolean, maxFrameBytes: Int)

    /** Waits for the peer's readiness signal on the push lane, or returns at once for a wire without one. */
    suspend fun awaitPushReadiness()

    /** Ends the node's half of the control lane once the node stops reading it. */
    suspend fun finishControl()

    /** Ends the node's half of the push lane once the connection is done. */
    suspend fun finishPush()

    /** The next call lane the peer opens, framed for the admin server by [framing]; null once the link ends. */
    suspend fun nextCall(framing: NodeCallFraming): NodeCallExchange?

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

    /** Writes the call's final response, which a chunking wire may split or replace when it is too large. */
    suspend fun respond(response: String)

    /** Ends the node's half of this lane. */
    suspend fun finish()
}

/**
 * How the admin server frames a call lane that arrives as raw bytes (an Iroh bi-stream). A wire whose lanes already
 * carry whole frames ignores it.
 */
internal fun interface NodeCallFraming {
    /** Frames the byte lane given by [read], [writeAll] and [finish] as one call exchange. */
    fun frame(read: suspend (UInt) -> ByteArray, writeAll: suspend (ByteArray) -> Unit, finish: suspend () -> Unit): NodeCallExchange
}
