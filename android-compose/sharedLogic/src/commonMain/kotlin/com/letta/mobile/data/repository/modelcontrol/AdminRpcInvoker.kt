package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One admin RPC call: `method` + JSON params → the result element. Throws
 * [ModelControlException] with the server's message on failure.
 */
fun interface AdminRpcInvoker {
    suspend fun invoke(method: String, params: JsonObject): JsonElement?

    companion object {
        /**
         * Over the session's channel transport (Iroh admin_rpc). [transport] is
         * read per call so hosts that swap transports (desktop) stay current.
         */
        fun overTransport(transport: () -> IChannelTransport?): AdminRpcInvoker = AdminRpcInvoker { method, params ->
            val channel = transport() ?: throw ModelControlException("Not connected to a host")
            val response = channel.adminRpc(method = method, path = "", body = params.toString())
            if (!response.success) throw ModelControlException(response.error ?: "$method failed")
            response.result
        }
    }
}

class ModelControlException(message: String) : Exception(message)
