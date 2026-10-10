package com.letta.mobile.data.compaction

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One completion of `/compact`, from its response frame or its streamed `slash_command_end`. */
data class CommandAnswer(val success: Boolean, val output: String?) {
    companion object {
        fun fromResponse(response: AppServerInboundFrame.ExecuteCommandResponse) =
            CommandAnswer(response.success, response.output ?: response.error)

        /** The `slash_command_end` for [command] on its own conversation, else null. */
        fun fromSlashEnd(frame: AppServerInboundFrame, command: AppServerCommand.ExecuteCommand): CommandAnswer? {
            val delta = (frame as? AppServerInboundFrame.StreamDelta)?.takeIf { it.runtime.sameConversation(command.runtime) }
                ?.delta as? JsonObject ?: return null
            if (delta.text("message_type") != SLASH_COMMAND_END) return null
            if (!delta.isFor(command.commandId)) return null
            return CommandAnswer(delta.bool("success") ?: true, delta.text("output"))
        }

        private fun AppServerRuntimeScope.sameConversation(other: AppServerRuntimeScope?): Boolean =
            other != null && conversationId == other.conversationId && agentId == other.agentId

        private fun JsonObject.isFor(commandId: String): Boolean =
            text("command_id") == commandId || text("input").orEmpty().trim().startsWith("/$commandId")

        private const val SLASH_COMMAND_END = "slash_command_end"
    }
}

/**
 * letta-mobile-57cta / 3kble: runs [command] and answers with its response or its
 * `slash_command_end` on the same conversation, whichever comes first. letta-code 0.26.1 (the
 * embedded Android runtime) never sends an `execute_command_response`; the delta is its only
 * completion. The stream is subscribed before the command is sent.
 */
suspend fun AppServerClient.executeCommandAnswer(command: AppServerCommand.ExecuteCommand): CommandAnswer = coroutineScope {
    val streamed = async(start = CoroutineStart.UNDISPATCHED) {
        events.mapNotNull { CommandAnswer.fromSlashEnd(it.frame, command) }.first()
    }
    val answered = async { CommandAnswer.fromResponse(executeCommand(command)) }
    select {
        streamed.onAwait { it }
        answered.onAwait { it }
    }.also { coroutineContext.cancelChildren() }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
