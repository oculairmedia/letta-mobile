package com.letta.mobile.data.node.info

import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.rpcwire.AdminRpcErrors
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The outcome of asking a connected node what it is. */
sealed interface NodeHandshakeResult {
    /** The node answered; [compatibility] is the verdict on it. */
    data class Checked(val compatibility: NodeCompatibility) : NodeHandshakeResult

    /** The node predates `node.info` (it does not know the method, or still gates it as an admin method). */
    data object Legacy : NodeHandshakeResult

    /** The call failed for another reason; [message] is the host's text or the failure's. */
    data class Failed(val message: String) : NodeHandshakeResult
}

/**
 * The client's side of the version handshake: on connect it reads `node.info` over [rpc] and runs
 * [NodeCompatibilityRule] against this build and the optional [reference] node.
 */
class NodeHandshake(
    private val rpc: AdminRpcInvoker,
    private val reference: suspend () -> NodeInfo? = { null },
) {
    /** Asks the node and judges it. Never throws except for cancellation. */
    suspend fun run(): NodeHandshakeResult = try {
        val result = rpc.invoke(NodeInfoWire.METHOD, JsonObject(emptyMap()))
            ?: return NodeHandshakeResult.Failed("${NodeInfoWire.METHOD} returned no result")
        val info = WIRE.decodeFromJsonElement(NodeInfo.serializer(), result)
        NodeHandshakeResult.Checked(NodeCompatibilityRule.evaluate(info, reference()))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        classify(error.message.orEmpty())
    }

    private companion object {
        val WIRE = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun classify(message: String): NodeHandshakeResult =
            if (AdminRpcErrors.isUnknownMethod(message) || message.startsWith("forbidden")) {
                NodeHandshakeResult.Legacy
            } else {
                NodeHandshakeResult.Failed(message.ifBlank { "${NodeInfoWire.METHOD} failed" })
            }
    }
}
