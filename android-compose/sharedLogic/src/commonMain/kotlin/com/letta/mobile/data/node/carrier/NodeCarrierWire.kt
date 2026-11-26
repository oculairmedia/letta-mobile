package com.letta.mobile.data.node.carrier

/** Wire names of the node protocol frames the session builds and reads. The frames themselves are today's node frames. */
object NodeCarrierWire {
    /** Every frame's discriminator. */
    const val TYPE: String = "type"

    /** Correlates a response with its request. */
    const val REQUEST_ID: String = "request_id"

    /** The first control frame of every session. */
    const val AUTH: String = "auth"

    /** The node's answer to [AUTH]. */
    const val AUTH_RESPONSE: String = "auth_response"

    /** An `admin_rpc` request, on its own call lane. */
    const val ADMIN_RPC: String = "admin_rpc"

    /** The final frame of an `admin_rpc` call lane. */
    const val ADMIN_RPC_RESPONSE: String = "admin_rpc_response"

    /** Ends a streamed `admin_rpc` early, written on its call lane. */
    const val ADMIN_RPC_CANCEL: String = "admin_rpc_cancel"

    /** `auth`: the bearer or pairing token, absent for the in-process shell. */
    const val TOKEN: String = "token"

    /** `auth`: a display name for the peer. */
    const val PEER_NAME: String = "peer_name"

    /** `auth` and `auth_response`: the advertised capabilities. */
    const val CAPABILITIES: String = "capabilities"

    /** Responses: whether the request succeeded. */
    const val SUCCESS: String = "success"

    /** Responses: the fixed failure label. */
    const val ERROR: String = "error"

    /** `auth_response`: the peer is paired (newly or already). */
    const val PAIRED: String = "paired"

    /** `admin_rpc`: the method name. */
    const val METHOD: String = "method"

    /** `admin_rpc`: the method's params object. */
    const val PARAMS: String = "params"

    /** `admin_rpc_response`: the method's result. */
    const val RESULT: String = "result"

    /** Transport capability a shell advertises so the Iroh carrier may split large frames into parts. */
    const val FRAME_PART_CAPABILITY: String = "frame_part"
}
