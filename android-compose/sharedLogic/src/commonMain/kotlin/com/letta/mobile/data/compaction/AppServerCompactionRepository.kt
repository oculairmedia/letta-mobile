package com.letta.mobile.data.compaction

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerRequestTimeoutException
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * letta-mobile-3kble: compaction over a direct App Server client.
 *
 * [supportedCommands] is the server's `device_status.supported_commands` for the conversation, or
 * null when no status has arrived yet (then `/compact` is simply tried). letta-code 0.26.1 (the
 * embedded Android runtime) runs `execute_command compact` but never answers it with an
 * `execute_command_response`; its `slash_command_end` delta on the conversation's stream is the
 * only completion signal, so the command's response and that delta race and the first one wins.
 * The App Server request times out at 30 s; a compaction still summarizing then is
 * [CompactionOutcome.Pending], not a failure.
 */
class AppServerCompactionRepository(
    private val client: suspend () -> AppServerClient?,
    private val supportedCommands: suspend (CompactionRequest) -> List<String>? = { null },
    private val requestId: (String) -> String = { "compact-$it" },
) : CompactionRepository {
    override suspend fun compact(request: CompactionRequest): CompactionOutcome {
        val appServer = client() ?: return CompactionOutcome.Unsupported
        val commands = supportedCommands(request)
        if (commands == null || COMPACT in commands) {
            viaCommand(appServer, request)?.let { return it }
        }
        return viaConversationCompact(appServer, request)
    }

    /** `/compact`; null when the server has no such command (then `conversation_compact` is tried). */
    private suspend fun viaCommand(appServer: AppServerClient, request: CompactionRequest): CompactionOutcome? {
        val command = AppServerCommand.ExecuteCommand(
            requestId = requestId(request.wireConversationId),
            commandId = COMPACT,
            args = request.mode?.wire,
            runtime = AppServerRuntimeScope(request.agentId.value, request.wireConversationId),
        )
        val answer = runCatchingCancellable { appServer.executeCommandAnswer(command) }.getOrElse { error ->
            return when (error) {
                is UnsupportedOperationException -> null
                is AppServerRequestTimeoutException -> CompactionOutcome.Pending
                else -> CompactionOutcome.Failed(error.message ?: "Compaction failed")
            }
        }
        if (!answer.success && answer.output.orEmpty().startsWith(UNKNOWN_COMMAND)) return null
        if (!answer.success) return CompactionOutcome.Failed(answer.output ?: "Compaction failed")
        return CompactCommandOutput.parse(answer.output).toOutcome()
    }

    private suspend fun viaConversationCompact(appServer: AppServerClient, request: CompactionRequest): CompactionOutcome {
        val response = runCatchingCancellable {
            appServer.conversationCompact(
                AppServerCommand.ConversationCompact(
                    requestId = requestId(request.wireConversationId),
                    conversationId = request.wireConversationId,
                    body = compactBody(request),
                ),
            )
        }.getOrElse { error ->
            return when (error) {
                is UnsupportedOperationException -> CompactionOutcome.Unsupported
                is AppServerRequestTimeoutException -> CompactionOutcome.Pending
                else -> CompactionOutcome.Failed(error.message ?: "Compaction failed")
            }
        }
        if (!response.success) return CompactionOutcome.Failed(response.error ?: "Compaction failed")
        return response.compaction.toResult().toOutcome()
    }

    private fun compactBody(request: CompactionRequest): JsonObject? {
        val isDefault = request.wireConversationId == ConversationCompactRpc.DEFAULT_CONVERSATION
        if (!isDefault && request.mode == null) return null
        return buildJsonObject {
            if (isDefault) put("agent_id", JsonPrimitive(request.agentId.value))
            request.mode?.let { put("compaction_settings", buildJsonObject { put("mode", JsonPrimitive(it.wire)) }) }
        }
    }

    private fun JsonObject?.toResult(): ConversationCompactResult {
        val before = this?.int("num_messages_before")
        val after = this?.int("num_messages_after")
        return ConversationCompactResult(
            path = CompactionPath.ConversationCompact.wire,
            summary = this?.text("summary"),
            messagesBefore = before,
            messagesAfter = after,
            noChange = before != null && before == after,
        )
    }

    private companion object {
        const val COMPACT = "compact"
        const val UNKNOWN_COMMAND = "Unknown command"
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
