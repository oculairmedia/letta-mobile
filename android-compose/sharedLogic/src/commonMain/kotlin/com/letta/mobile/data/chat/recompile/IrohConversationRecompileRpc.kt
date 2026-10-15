package com.letta.mobile.data.chat.recompile

import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * letta-mobile-by4vm.51: the host's `conversation.recompile` admin RPC, as clients call it and the
 * host's handler reads it. The host relays it to App Server `conversation_recompile`. Params:
 * `agent_id`, `conversation_id`, and the optional `dry_run` (compile without saving). The result
 * carries the compiled system prompt under [PROMPT].
 *
 * A host that relays it advertises [CAPABILITY] in its `auth_response`, so clients offer the action
 * only to hosts that can run it (a wrapper deployed before the relay does not advertise it).
 */
object IrohConversationRecompileRpc {
    const val METHOD = "conversation.recompile"
    const val CAPABILITY = "conversation_recompile_v1"
    const val AGENT_ID = "agent_id"
    const val CONVERSATION_ID = "conversation_id"
    const val DRY_RUN = "dry_run"
    const val PROMPT = "prompt"

    fun path(request: ConversationRecompileRequest): String = "/v1/conversations/${request.conversationId}/recompile"

    fun params(request: ConversationRecompileRequest, dryRun: Boolean): JsonObject = buildJsonObject {
        put(CONVERSATION_ID, request.conversationId)
        request.agentId.takeIf { it.isNotBlank() }?.let { put(AGENT_ID, it) }
        if (dryRun) put(DRY_RUN, true)
    }

    fun result(prompt: String?, dryRun: Boolean): JsonObject = buildJsonObject {
        put(PROMPT, prompt)
        put(DRY_RUN, dryRun)
    }

    /** The compiled prompt in a host's answer; empty when the host sent none. */
    fun promptOf(result: JsonElement?): String =
        ((result as? JsonObject)?.get(PROMPT) as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/**
 * letta-mobile-by4vm.51: `/recompile` and the conversation inspector's Recompile over an Iroh
 * host's `conversation.recompile` relay. [supported] reads the host's advertised capability; a call
 * to a host without it throws [UnsupportedOperationException] (the recompile controller shows its
 * unavailable reason) instead of sending a method the host would refuse.
 */
class IrohConversationRecompileClient(private val transport: IrohAdminRpcLink) : ConversationRecompileGateway {
    val supported: Boolean get() = IrohConversationRecompileRpc.CAPABILITY in transport.hostCapabilities

    override suspend fun recompileConversation(request: ConversationRecompileRequest) {
        recompile(request, dryRun = false)
    }

    /** Returns the compiled system prompt; throws with the host's reason when it refuses. */
    suspend fun recompile(request: ConversationRecompileRequest, dryRun: Boolean): String {
        if (!supported) throw UnsupportedOperationException(HOST_NEEDS_UPDATE)
        val response = transport.adminRpc(
            method = IrohConversationRecompileRpc.METHOD,
            path = IrohConversationRecompileRpc.path(request),
            body = IrohConversationRecompileRpc.params(request, dryRun).toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc ${IrohConversationRecompileRpc.METHOD} failed")
        return IrohConversationRecompileRpc.promptOf(response.result)
    }

    companion object {
        const val HOST_NEEDS_UPDATE = "This host can't recompile yet: update its Letta Mobile wrapper."
    }
}

/**
 * The part of an Iroh connection [IrohConversationRecompileClient] uses: one admin RPC, and what the
 * host advertised. It mirrors [IChannelTransport.adminRpc] so this package does not depend on
 * `repository.iroh` (letta-mobile-by4vm.27 layering ratchet: no mutually importing packages).
 */
interface IrohAdminRpcLink {
    val hostCapabilities: Set<String>

    suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse
}

/** This transport as an [IrohAdminRpcLink]. */
fun IChannelTransport.asAdminRpcLink(): IrohAdminRpcLink = object : IrohAdminRpcLink {
    override val hostCapabilities: Set<String> get() = this@asAdminRpcLink.hostCapabilities

    override suspend fun adminRpc(method: String, path: String, body: String?) =
        this@asAdminRpcLink.adminRpc(method, path, body)
}
