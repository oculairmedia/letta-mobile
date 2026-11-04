package com.letta.mobile.cli.commands.a2a

import com.letta.mobile.data.controller.node.iroh.ConversationChangeTarget
import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.messaging.AgentMessageClientId
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.transport.iroh.IrohAgentMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** Tracks only accepted inputs on their resolved inbox, never a roster or a client subscription. */
internal class A2aArrivalObserver(
    private val scope: CoroutineScope,
    private val store: LocalBackendAdminStore,
    private val target: ConversationChangeTarget,
    private val persisted: suspend (String) -> Unit,
) {
    fun observe(message: IrohAgentMessage, conversationId: String) {
        scope.launch(Dispatchers.IO) {
            if (!isInbox(message.toAgentId, conversationId)) return@launch
            val clientId = AgentMessageClientId.encode(message.msgId, message.fromAgentId, message.toAgentId)
            while (isActive) {
                val found = runCatching { findSource(message.toAgentId, conversationId, clientId) }
                    .getOrElse { failure ->
                        if (failure is CancellationException) throw failure
                        null
                    }
                if (found != null && announce(message, conversationId, clientId, found)) return@launch
                delay(RECHECK_MS)
            }
        }
    }

    private suspend fun announce(message: IrohAgentMessage, conversationId: String, clientId: String, source: String): Boolean =
        runCatching {
            persisted(message.msgId)
            val now = Instant.now().toString()
            target.broadcast(buildJsonObject {
                put("v", 1)
                put("type", "conversation_activity")
                put("id", "activity-${message.msgId}")
                put("ts", now)
                put("agent_id", message.toAgentId)
                put("conversation_id", conversationId)
                put("milestone", "persisted")
                put("source_message_id", source)
                put("client_message_id", clientId)
                put("at", now)
            }.toString())
            true
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            false
        }

    private fun findSource(agentId: String, conversationId: String, clientId: String): String? {
        if (!isInbox(agentId, conversationId)) return null
        return store.committedUserMessageId(ConversationId(conversationId), AgentId(agentId), MessageId(clientId))?.takeIf { it.isNotBlank() }
    }

    private fun isInbox(agentId: String, conversationId: String): Boolean {
        val conversation = store.conversationRecord(ConversationId(conversationId), AgentId(agentId)) ?: return false
        val tags = conversation["tags"] as? kotlinx.serialization.json.JsonArray ?: return false
        return JsonPrimitive("lane:inbox") in tags
    }

    private companion object {
        const val RECHECK_MS = 1_000L
    }
}
