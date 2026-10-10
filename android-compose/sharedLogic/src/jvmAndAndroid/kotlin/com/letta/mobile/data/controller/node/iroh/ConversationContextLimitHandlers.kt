package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.compaction.ConversationCompactRpc
import com.letta.mobile.data.compaction.executeCommandAnswer
import com.letta.mobile.data.context.limit.ContextLimitOutcome
import com.letta.mobile.data.context.limit.ContextLimitResult
import com.letta.mobile.data.context.limit.ContextLimitRpc
import com.letta.mobile.data.context.limit.toOutcome
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** letta-mobile-joigh: one `conversation.context_limit` request, validated. */
internal data class ContextLimitParams(val agentId: String, val conversationId: String, val tokens: Int) {
    companion object {
        fun from(params: JsonObject?): ContextLimitParams {
            val agentId = params.requireParam(AdminParamKey(ContextLimitRpc.AGENT_ID))
            val conversationId = param(params, AdminParamKey(ContextLimitRpc.CONVERSATION_ID))
                ?.takeIf { it.isNotBlank() } ?: ConversationCompactRpc.DEFAULT_CONVERSATION
            val rawTokens = params.requireParam(AdminParamKey(ContextLimitRpc.TOKENS))
            val tokens = rawTokens.toIntOrNull()?.takeIf { it > 0 } ?: adminError("invalid_tokens: $rawTokens (a positive token count)")
            return ContextLimitParams(agentId, conversationId, tokens)
        }
    }
}

/**
 * letta-mobile-joigh: relays a context-limit change from an Iroh client to this node's App Server
 * as letta-code's own `/context-limit <tokens>` (`execute_command context-limit`): letta-code
 * validates the value (its 30,000-token floor, the model's catalog window) and chooses the scope —
 * the agent on its `default` conversation, else that conversation's own limit — exactly as its
 * TUI does. No `--override` is ever sent.
 *
 * An App Server without the command (letta-code before `/context-limit`, e.g. the embedded 0.26.1
 * runtime) answers "Unknown command": that is `capability_unavailable`, so a client hides the
 * slider. Gated by [IrohPeerCapabilities.CONVERSATION_MANAGE], like `model.update`.
 * Errors carry fixed sentences plus letta-code's own text; params are never logged.
 */
internal object ConversationContextLimitHandlers {
    val methods: Set<String> = setOf(ContextLimitRpc.METHOD)

    fun register(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        if (nativeClient == null) {
            CapabilityUnavailable.register(router, methods, "native App Server client")
            return
        }
        router.register(ContextLimitRpc.METHOD) { params -> apply(nativeClient, ContextLimitParams.from(params)) }
    }

    internal suspend fun apply(client: AppServerClient, request: ContextLimitParams): JsonElement {
        val outcome = runGuarded {
            client.executeCommandAnswer(
                AppServerCommand.ExecuteCommand(
                    requestId = NativeAdmin.requestId(),
                    commandId = ContextLimitRpc.COMMAND_ID,
                    args = request.tokens.toString(),
                    runtime = AppServerRuntimeScope(request.agentId, request.conversationId),
                ),
            ).toOutcome(request.tokens)
        }
        val result = when (outcome) {
            is ContextLimitOutcome.Applied -> outcome.result
            ContextLimitOutcome.Unsupported -> adminError("capability_unavailable: this App Server has no /context-limit")
            is ContextLimitOutcome.Failed -> adminError("context_limit_failed: ${outcome.message}")
        }
        Telemetry.event("IrohNode", "conversation_context_limit.ok", "scope" to result.appliedTo)
        return AppServerProtocol.json.encodeToJsonElement(ContextLimitResult.serializer(), result)
    }

    private suspend fun <T> runGuarded(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unsupported: UnsupportedOperationException) {
        fail("unsupported", unsupported, "capability_unavailable: the App Server cannot run commands")
    } catch (timeout: AppServerRequestTimeoutException) {
        fail("timeout", timeout, "context_limit_failed: the App Server did not answer")
    } catch (error: Exception) {
        fail("failed", error, "context_limit_failed: the host could not reach the App Server")
    }

    private fun fail(outcome: String, error: Exception, message: String): Nothing {
        Telemetry.event(
            "IrohNode", "conversation_context_limit.$outcome",
            "class" to error::class.simpleName,
            level = Telemetry.Level.WARN,
        )
        adminError(message)
    }
}
