package com.letta.mobile.data.node.carrier

/**
 * A node end that reassembles chunked frames from its peer (the Iroh carrier's `frame_part`s). The node decides how
 * large one reassembled frame may grow, because only the node knows whether the peer is authenticated: until it is
 * (and again if it stops being), a peer must not make the node hold more than one plain frame per lane. A carrier
 * that moves whole frames has nothing to bound and does not implement this.
 */
internal interface NodePeerReassemblyBound {
    /**
     * Bounds every frame reassembled from the peer, on the control lane and on each call lane, by [maxBytes], read at
     * every part, and hands the node's own answer to "does this peer accept `frame_part`s" ([frameParts]) to the
     * link, so the link splits its writes on the same answer the node fits its responses to. Once bound, the link
     * never decides either on its own.
     */
    fun boundReassembly(maxBytes: () -> Int, frameParts: () -> Boolean)
}
