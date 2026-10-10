package com.letta.mobile.data.context.limit

import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.transport.iroh.AdminRpcErrors
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-joigh: the context limit through the Iroh host's `conversation.context_limit`.
 *
 * A host that predates the relay answers "Unknown method", one without a native App Server client
 * or with a letta-code that has no `/context-limit` answers `capability_unavailable`, and a
 * transport with no admin_rpc at all says so: all three are [ContextLimitOutcome.Unsupported], so
 * the slider hides rather than failing (the Tool Detail pattern, #1827).
 */
class AdminRpcContextLimitRepository(
    private val rpc: AdminRpcInvoker,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ContextLimitRepository {
    override suspend fun apply(request: ContextLimitRequest): ContextLimitOutcome {
        val params = buildJsonObject {
            put(ContextLimitRpc.AGENT_ID, request.agentId.value)
            put(ContextLimitRpc.CONVERSATION_ID, request.wireConversationId)
            put(ContextLimitRpc.TOKENS, request.tokens)
        }
        return runCatchingCancellable { rpc.invoke(ContextLimitRpc.METHOD, params) }.fold(
            onSuccess = { result ->
                result?.let { ContextLimitOutcome.Applied(json.decodeFromJsonElement(ContextLimitResult.serializer(), it)) }
                    ?: ContextLimitOutcome.Failed("The host returned no result")
            },
            onFailure = { classify(it.message.orEmpty()) },
        )
    }

    private fun classify(message: String): ContextLimitOutcome = when {
        AdminRpcErrors.isUnknownMethod(message) -> ContextLimitOutcome.Unsupported
        message.contains(CAPABILITY_UNAVAILABLE) || message.contains(NO_ADMIN_RPC) -> ContextLimitOutcome.Unsupported
        else -> ContextLimitOutcome.Failed(message.removePrefix(FAILED_PREFIX).ifBlank { "Couldn't change the context limit" })
    }

    private companion object {
        const val CAPABILITY_UNAVAILABLE = "capability_unavailable"
        const val NO_ADMIN_RPC = "admin_rpc is not supported"
        const val FAILED_PREFIX = "context_limit_failed: "
    }
}
