package com.letta.mobile.data.node.carrier

import com.letta.mobile.data.serialization.LettaJson
import kotlin.jvm.JvmInline
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One node protocol frame: a JSON object with a `type`. A carrier moves frames and never reads or rewrites them;
 * the frame is immutable data, so handing it across a carrier never shares state between the shell and the node.
 */
@JvmInline
value class NodeFrame(val json: JsonObject) {
    /** The frame's `type`, or null when it has none (a malformed frame the receiver rejects). */
    val type: String? get() = string(NodeCarrierWire.TYPE)

    /** The frame's `request_id`, which correlates a control response or an admin_rpc response with its request. */
    val requestId: String? get() = string(NodeCarrierWire.REQUEST_ID)

    /** The frame as wire text, for carriers that serialize (Iroh writes it through its frame codec). */
    fun encode(): String = json.toString()

    private fun string(key: String): String? = (json[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** Parses wire text into a frame; throws when the text is not a JSON object. */
        fun decode(text: String): NodeFrame = NodeFrame(LettaJson.wire.parseToJsonElement(text).jsonObject)
    }
}

/**
 * The three lanes of a node connection. Frames on one lane arrive in the order they were sent; there is no order
 * between lanes, exactly as between two QUIC streams.
 */
enum class NodeLane {
    /** Requests and their responses, both ways: auth, runtime_start, input, sync, abort, queue controls, app tool replies. */
    CONTROL,

    /** Node-to-shell pushes: turn frames (`stream_delta`), `feed_delta`, agent and conversation events, shared-state updates. */
    PUSH,

    /** One `admin_rpc` call: a request, then its frames, ending in one `admin_rpc_response`. Each call has its own lane. */
    CALL,
}

/** A frame the shell received, with the lane it came on ([NodeLane.CONTROL] or [NodeLane.PUSH]). */
data class NodeInbound(val lane: NodeLane, val frame: NodeFrame)

/**
 * Where a node is dialled. Opaque to everything but the carrier that dials it: an Iroh carrier reads an `iroh://`
 * ticket or the `<node id hex>@host:port` short form; the in-process carrier reads `in-process:<name>`.
 */
@JvmInline
value class NodeAddress(val value: String)

/**
 * An identity the carrier itself verified, never one a frame claimed. On the shell's side it is the node it reached;
 * on the node's side it is the peer that dialled (the Iroh endpoint id in hex, or [LOCAL_SHELL] for the in-process
 * carrier). The node keys pairing, rate limits and viewer registration by it.
 */
@JvmInline
value class NodePeerIdentity(val value: String) {
    companion object {
        /** The shell in the node's own process. The node admits it as an admin peer by construction. */
        val LOCAL_SHELL: NodePeerIdentity = NodePeerIdentity("local-shell")
    }
}

/**
 * Why a link ended: an application close code and a short fixed reason label (never derived from a secret). The node
 * closes with the codes below; a carrier delivers the code and reason to the other end unchanged.
 */
data class NodeLinkClosure(val code: Long, val reason: String) {
    companion object {
        /** An orderly close by either end. On the shell's side this is a detach: running turns continue on the node. */
        val NORMAL: NodeLinkClosure = NodeLinkClosure(0L, "closed")

        /** The node closed after repeated failed auth frames on one connection. */
        val AUTH_FAILURES: NodeLinkClosure = NodeLinkClosure(4401L, "auth_failures")

        /** The peer's pairing was revoked while it was connected. */
        val PEER_REVOKED: NodeLinkClosure = NodeLinkClosure(4403L, "peer_revoked")

        /** The node rate-limited the peer's auth attempts. */
        val AUTH_RATE_LIMITED: NodeLinkClosure = NodeLinkClosure(4429L, "auth_rate_limited")

        /** The transport underneath went away without a close from either end (network loss, process death). */
        val LOST: NodeLinkClosure = NodeLinkClosure(1L, "lost")
    }
}
