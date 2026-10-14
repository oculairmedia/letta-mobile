package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.chat.recompile.IrohConversationRecompileRpc
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerConversationRecompile
import com.letta.mobile.data.transport.appserver.AppServerConversationRecompileBody
import com.letta.mobile.data.transport.appserver.AppServerConversationRecompileResponse
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** letta-mobile-by4vm.51: one `conversation.recompile` request, validated. */
internal data class RecompileParams(val agentId: String, val conversationId: String, val dryRun: Boolean) {
    companion object {
        fun from(params: JsonObject?): RecompileParams = RecompileParams(
            // Required, so an agent-scoped peer's scope guard always sees the agent it touches.
            agentId = params.requireParam(AdminParamKey(IrohConversationRecompileRpc.AGENT_ID)),
            conversationId = params.requireParam(AdminParamKey(IrohConversationRecompileRpc.CONVERSATION_ID)),
            dryRun = param(params, AdminParamKey(IrohConversationRecompileRpc.DRY_RUN)) == "true",
        )
    }
}

/**
 * letta-mobile-by4vm.51: relays a system-prompt recompile from an Iroh client to this node's App
 * Server as `conversation_recompile` (letta-code `protocol_v2` `ConversationRecompileCommand`),
 * carrying `agent_id` and `dry_run` in its body. The answer is the compiled prompt
 * ([IrohConversationRecompileRpc.result]); a dry run compiles without saving.
 *
 * Advertises [IrohConversationRecompileRpc.CAPABILITY] only when an App Server is attached, so a
 * client offers Recompile only to hosts that can run it. Gated by
 * [IrohPeerCapabilities.CONVERSATION_MANAGE], like `conversation.compact`. Errors carry fixed
 * sentences plus letta-code's own refusal text; params are never logged.
 */
internal object ConversationRecompileHandlers {
    val methods: Set<String> = setOf(IrohConversationRecompileRpc.METHOD)

    fun register(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        if (nativeClient == null) {
            CapabilityUnavailable.register(router, methods, "native App Server client")
            return
        }
        router.register(IrohConversationRecompileRpc.METHOD) { params -> recompile(nativeClient, RecompileParams.from(params)) }
        router.featureCapabilities += IrohConversationRecompileRpc.CAPABILITY
    }

    internal suspend fun recompile(client: AppServerClient, request: RecompileParams): JsonElement {
        val response = runGuarded { client.conversationRecompile(command(request)) }
        if (!response.success) {
            Telemetry.event("IrohNode", "conversation_recompile.refused", level = Telemetry.Level.WARN)
            adminError("recompile_failed: ${response.error ?: "the App Server gave no reason"}")
        }
        Telemetry.event("IrohNode", "conversation_recompile.ok", "dry_run" to request.dryRun)
        return IrohConversationRecompileRpc.result(response.result, request.dryRun)
    }

    private fun command(request: RecompileParams) = AppServerConversationRecompile(
        requestId = NativeAdmin.requestId(),
        conversationId = request.conversationId,
        // Unset options are not sent: `dry_run` only when asked for.
        body = AppServerConversationRecompileBody(agentId = request.agentId, dryRun = true.takeIf { request.dryRun }),
    )

    private suspend fun runGuarded(block: suspend () -> AppServerConversationRecompileResponse): AppServerConversationRecompileResponse = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unsupported: UnsupportedOperationException) {
        fail("unsupported", unsupported, "capability_unavailable: the App Server cannot recompile conversations")
    } catch (timeout: AppServerRequestTimeoutException) {
        fail("timeout", timeout, "recompile_failed: the App Server did not answer")
    } catch (error: Exception) {
        fail("failed", error, "recompile_failed: the host could not reach the App Server")
    }

    private fun fail(outcome: String, error: Exception, message: String): Nothing {
        Telemetry.event(
            "IrohNode", "conversation_recompile.$outcome",
            "class" to error::class.simpleName,
            level = Telemetry.Level.WARN,
        )
        adminError(message)
    }
}
