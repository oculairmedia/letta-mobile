package com.letta.mobile.data.compaction

import com.letta.mobile.data.repository.modelcontrol.AdminRpcInvoker
import com.letta.mobile.data.transport.iroh.AdminRpcErrors
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-3kble: compaction through the Iroh host's `conversation.compact` admin_rpc.
 *
 * A host that predates the relay answers "Unknown method" and one without a native App Server
 * client `capability_unavailable`: both are [CompactionOutcome.Unsupported], so the button hides
 * rather than failing (the Tool Detail pattern, #1827). A transport with no admin_rpc at all (a
 * direct App Server session) is unsupported the same way.
 */
class AdminRpcCompactionRepository(
    private val rpc: AdminRpcInvoker,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CompactionRepository {
    override suspend fun compact(request: CompactionRequest): CompactionOutcome {
        val params = buildJsonObject {
            put(ConversationCompactRpc.AGENT_ID, request.agentId.value)
            put(ConversationCompactRpc.CONVERSATION_ID, request.wireConversationId)
            request.mode?.let { put(ConversationCompactRpc.MODE, it.wire) }
        }
        return runCatchingCancellable { rpc.invoke(ConversationCompactRpc.METHOD, params) }.fold(
            onSuccess = { result ->
                result?.let { json.decodeFromJsonElement(ConversationCompactResult.serializer(), it).toOutcome() }
                    ?: CompactionOutcome.Failed("The host returned no compaction result")
            },
            onFailure = { classify(it.message.orEmpty()) },
        )
    }

    private fun classify(message: String): CompactionOutcome = when {
        AdminRpcErrors.isUnknownMethod(message) -> CompactionOutcome.Unsupported
        message.contains(CAPABILITY_UNAVAILABLE) || message.contains(NO_ADMIN_RPC) -> CompactionOutcome.Unsupported
        message.startsWith(PENDING) -> CompactionOutcome.Pending
        else -> CompactionOutcome.Failed(message.removePrefix(FAILED_PREFIX).ifBlank { "Compaction failed" })
    }

    private companion object {
        const val CAPABILITY_UNAVAILABLE = "capability_unavailable"
        const val NO_ADMIN_RPC = "admin_rpc is not supported"
        const val PENDING = "compaction_pending"
        const val FAILED_PREFIX = "compaction_failed: "
    }
}
