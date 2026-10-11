package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.compaction.CompactCommandOutput
import com.letta.mobile.data.compaction.CompactionMode
import com.letta.mobile.data.compaction.CompactionPath
import com.letta.mobile.data.compaction.ConversationCompactResult
import com.letta.mobile.data.compaction.ConversationCompactRpc
import com.letta.mobile.data.compaction.executeCommandAnswer
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerProtocol
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** letta-mobile-57cta: one `conversation.compact` request, validated. */
internal data class CompactRequest(val agentId: String, val conversationId: String, val mode: CompactionMode?) {
    val query: AgentContextQuery get() = AgentContextQuery(agentId, conversationId)

    companion object {
        fun from(params: JsonObject?): CompactRequest {
            val agentId = params.requireParam(AdminParamKey(ConversationCompactRpc.AGENT_ID))
            val conversationId = param(params, AdminParamKey(ConversationCompactRpc.CONVERSATION_ID))
                ?.takeIf { it.isNotBlank() } ?: ConversationCompactRpc.DEFAULT_CONVERSATION
            val rawMode = param(params, AdminParamKey(ConversationCompactRpc.MODE))?.takeIf { it.isNotBlank() }
            val mode = rawMode?.let { CompactionMode.fromWire(it) ?: adminError("invalid_mode: $it (all or sliding_window)") }
            return CompactRequest(agentId, conversationId, mode)
        }
    }
}

/**
 * letta-mobile-57cta: relays a manual compaction from an Iroh client to this node's App Server.
 *
 * `execute_command compact` first — letta-code's `/compact`, which runs the pre-compact hooks and
 * the compaction-event reflection a compaction run from any other client gets — then
 * `conversation_compact` only when the App Server does not implement the command (an
 * `UnsupportedOperationException` or an "Unknown command" answer). A real compaction failure is
 * reported, never retried through the other path: that would compact twice.
 *
 * A manual compaction streams no `usage_statistics`, so a client's streamed total goes stale until
 * the next turn. With a local-backend store the answer carries the transcript's estimated tokens
 * before and after, which a client applies exactly as it applies an automatic compaction's
 * `compaction_stats`.
 *
 * Gated by [IrohPeerCapabilities.CONVERSATION_MANAGE]. Errors carry fixed sentences plus
 * letta-code's own error text; params and summaries are never logged.
 */
internal object ConversationCompactHandlers {
    val methods: Set<String> = setOf(ConversationCompactRpc.METHOD)

    fun register(router: AdminRpcRouter, nativeClient: AppServerClient?, store: LocalBackendAdminStore?) {
        if (nativeClient == null) {
            CapabilityUnavailable.register(router, methods, "native App Server client")
            return
        }
        router.register(ConversationCompactRpc.METHOD) { params -> compact(nativeClient, store, CompactRequest.from(params)) }
    }

    internal suspend fun compact(client: AppServerClient, store: LocalBackendAdminStore?, request: CompactRequest): JsonElement {
        val tokensBefore = store?.transcriptTokens(request.query)
        val result = runGuarded { viaCommand(client, request) ?: viaConversationCompact(client, request) }
        val tokensAfter = store?.transcriptTokens(request.query)
        Telemetry.event(
            "IrohNode", "conversation_compact.ok",
            "path" to result.path,
            "noChange" to result.noChange,
        )
        val estimated = result.copy(
            contextTokensBefore = tokensBefore?.takeIf { tokensAfter != null },
            contextTokensAfter = tokensAfter?.takeIf { tokensBefore != null },
        )
        return AppServerProtocol.json.encodeToJsonElement(ConversationCompactResult.serializer(), estimated)
    }

    /** `/compact`; null when the App Server has no such command. */
    private suspend fun viaCommand(client: AppServerClient, request: CompactRequest): ConversationCompactResult? {
        val answer = try {
            // letta-code 0.26.1 answers only with slash_command_end, never execute_command_response.
            client.executeCommandAnswer(
                AppServerCommand.ExecuteCommand(
                    requestId = NativeAdmin.requestId(),
                    commandId = COMPACT_COMMAND,
                    args = request.mode?.wire,
                    runtime = AppServerRuntimeScope(request.agentId, request.conversationId),
                ),
            )
        } catch (unsupported: UnsupportedOperationException) {
            return null
        }
        val output = answer.output
        if (!answer.success && output.orEmpty().startsWith(UNKNOWN_COMMAND)) return null
        if (!answer.success) adminError("compaction_failed: ${output ?: "the App Server could not compact"}")
        return CompactCommandOutput.parse(output)
    }

    private suspend fun viaConversationCompact(client: AppServerClient, request: CompactRequest): ConversationCompactResult {
        val response = client.conversationCompact(
            AppServerCommand.ConversationCompact(
                requestId = NativeAdmin.requestId(),
                conversationId = request.conversationId,
                body = compactBody(request),
            ),
        )
        if (!response.success) adminError("compaction_failed: ${response.error ?: "the App Server could not compact"}")
        val compaction = response.compaction
        val before = compaction?.int("num_messages_before")
        val after = compaction?.int("num_messages_after")
        return ConversationCompactResult(
            path = CompactionPath.ConversationCompact.wire,
            summary = compaction?.text("summary"),
            messagesBefore = before,
            messagesAfter = after,
            noChange = before != null && before == after,
        )
    }

    /** letta-code needs `agent_id` in the body for the bare `default` conversation. */
    private fun compactBody(request: CompactRequest): JsonObject? {
        val defaultConversation = request.conversationId == ConversationCompactRpc.DEFAULT_CONVERSATION
        if (!defaultConversation && request.mode == null) return null
        return buildJsonObject {
            if (defaultConversation) put("agent_id", JsonPrimitive(request.agentId))
            request.mode?.let { put("compaction_settings", buildJsonObject { put("mode", JsonPrimitive(it.wire)) }) }
        }
    }

    private suspend fun <T> runGuarded(block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (refused: IllegalArgumentException) {
        throw refused
    } catch (timeout: AppServerRequestTimeoutException) {
        fail("timeout", timeout, "compaction_pending: the App Server is still compacting; the result arrives with the next turn")
    } catch (unsupported: UnsupportedOperationException) {
        fail("unsupported", unsupported, "capability_unavailable: the App Server cannot compact conversations")
    } catch (error: Exception) {
        fail("failed", error, "compaction_failed: the host could not reach the App Server")
    }

    private fun fail(outcome: String, error: Exception, message: String): Nothing {
        Telemetry.event(
            "IrohNode", "conversation_compact.$outcome",
            "class" to error::class.simpleName,
            level = Telemetry.Level.WARN,
        )
        adminError(message)
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private const val COMPACT_COMMAND = "compact"
    private const val UNKNOWN_COMMAND = "Unknown command"
}
