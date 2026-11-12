package com.letta.mobile.data.controller.node.iroh.mounts

import com.letta.mobile.data.controller.node.iroh.NativeAdmin
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonPrimitive

/**
 * The host's own records of which conversations and agents exist, consulted only by the mount authority
 * (finding F8: a binding needs an existing conversation of the addressed agent, a default an existing agent).
 * A lookup that fails throws; the authority then answers `unavailable` with the fixed `authority` reason.
 */
interface CanvasMountDirectory {
    /** The agent that owns [conversation], or null when the host has no such conversation. */
    suspend fun ownerOf(conversation: ConversationId): AgentId?

    /** Whether the host has [agent]. */
    suspend fun hasAgent(agent: AgentId): Boolean

    /** Directories backed by host services. */
    companion object {
        /**
         * The App Server's own `conversation_retrieve` and `agent_retrieve`. An unsuccessful answer counts as
         * absent, so it denies; default-conversation aliases (`conv-default-<agent>`) are not resolved and deny.
         */
        fun appServer(client: AppServerClient): CanvasMountDirectory = AppServerMountDirectory(client)
    }
}

private class AppServerMountDirectory(private val client: AppServerClient) : CanvasMountDirectory {
    override suspend fun ownerOf(conversation: ConversationId): AgentId? {
        val response = client.conversationRetrieve(
            AppServerCommand.ConversationRetrieve(requestId = NativeAdmin.requestId(), conversationId = conversation.value),
        )
        val owner = response.conversation?.get("agent_id") as? JsonPrimitive
        return owner?.takeIf { response.success && it.isString }?.content?.let(::AgentId)
    }

    override suspend fun hasAgent(agent: AgentId): Boolean {
        val response = client.agentRetrieve(AppServerCommand.AgentRetrieve(requestId = NativeAdmin.requestId(), agentId = agent.value))
        return response.success && response.agent != null
    }
}
