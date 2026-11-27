package com.letta.mobile.data.node.info

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.rpcwire.AdminRpcErrors
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One `node.info` call: the method and its params in, the result element out; failures throw with the host's text. */
fun interface NodeInfoRpc {
    /** Calls [method] with [params] and returns its result, or null when it has none. */
    suspend fun invoke(method: String, params: JsonObject): JsonElement?
}

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
    private val rpc: NodeInfoRpc,
    private val reference: suspend () -> NodeInfo? = { null },
) {
    /** Asks the node and judges it. Never throws except for cancellation. */
    suspend fun run(): NodeHandshakeResult {
        val info = try {
            val result = rpc.invoke(NodeInfoWire.METHOD, JsonObject(emptyMap()))
                ?: return NodeHandshakeResult.Failed("${NodeInfoWire.METHOD} returned no result")
            if (result !is JsonObject || STORE_FORMAT_FIELD !in result) {
                return NodeHandshakeResult.Failed("${NodeInfoWire.METHOD} did not report $STORE_FORMAT_FIELD")
            }
            WIRE.decodeFromJsonElement(NodeInfo.serializer(), result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            return classify(error.message.orEmpty())
        }
        return NodeHandshakeResult.Checked(NodeCompatibilityRule.evaluate(info, referenceOrNull()))
    }

    /** The reference is optional: a failure to read it leaves the target judged on its own. */
    private suspend fun referenceOrNull(): NodeInfo? = try {
        reference()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
        null
    }

    private companion object {
        val WIRE = LettaJson.adminRpc
        const val STORE_FORMAT_FIELD = "store_format"

        fun classify(message: String): NodeHandshakeResult =
            if (AdminRpcErrors.isUnknownMethod(message) || message.startsWith("forbidden")) {
                NodeHandshakeResult.Legacy
            } else {
                NodeHandshakeResult.Failed(message.ifBlank { "${NodeInfoWire.METHOD} failed" })
            }
    }
}
