package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.node.iroh.LocalBackendAdminStore
import com.letta.mobile.data.controller.node.iroh.MessagePage
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Read side of the bundled local backend. The Letta Code child remains the
 * only writer; this gateway projects its on-disk store for desktop admin reads.
 */
internal class DesktopLocalBackendAdminGateway(
    backendDirectory: File,
) : DesktopAdminChatGateway {
    private val store = LocalBackendAdminStore(backendDirectory)
    private val pendingConversations = ConcurrentHashMap<String, Conversation>()

    override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> {
        val stored = store.listConversationsProjected(
            agentId = null,
            archiveStatus = archiveStatus,
            limit = limit,
            offset = 0,
        ).decodeList(Conversation.serializer())
        val storedIds = stored.mapTo(mutableSetOf()) { it.id.value }
        return (pendingConversations.values.filter { it.id.value !in storedIds } + stored)
            .take(limit)
    }

    override suspend fun getConversation(conversationId: String): Conversation =
        pendingConversations[conversationId]
            ?: listConversations(limit = 10_000, archiveStatus = null)
                .firstOrNull { it.id.value == conversationId }
            ?: throw NoSuchElementException("Local conversation $conversationId was not found")

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        val agentId = runCatching { getConversation(conversationId).agentId.value }.getOrNull()
        return store.listMessagesProjected(
            conversationId = conversationId,
            agentId = agentId,
            page = MessagePage(limit = limit, before = null, after = after, order = order),
        ).decodeList(LettaMessage.serializer())
    }

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        if (conversationId != null) {
            return listConversationMessages(conversationId, limit, after = null, order = order)
        }
        val messages = mutableListOf<LettaMessage>()
        for (conversation in listConversations(limit = 10_000, archiveStatus = null)) {
            if (conversation.agentId.value == agentId) {
                messages += listConversationMessages(
                    conversation.id.value,
                    limit = null,
                    after = null,
                    order = order,
                )
            }
        }
        return limit?.let(messages::takeLast) ?: messages
    }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation {
        val conversationId = "local-conv-${UUID.randomUUID()}"
        val agentName = listAgents().firstOrNull { it.id.value == agentId }?.name
        return Conversation(
            id = ConversationId(conversationId),
            agentId = AgentId(agentId),
            agentName = agentName,
            summary = summary,
        ).also { pendingConversations[conversationId] = it }
    }

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        getConversation(conversationId)

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation {
        val updated = getConversation(conversationId).copy(archived = archived)
        if (pendingConversations.containsKey(conversationId)) pendingConversations[conversationId] = updated
        return updated
    }

    override suspend fun deleteConversation(conversationId: String) {
        if (pendingConversations.remove(conversationId) == null) {
            throw UnsupportedOperationException("Persisted local conversation deletion is not supported yet")
        }
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent =
        throw UnsupportedOperationException("Local agent creation is not supported yet")

    override suspend fun listLlmModels(): List<LlmModel> = emptyList()

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = throw UnsupportedOperationException("Turns are owned by the bundled App Server")

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        flowOf(TimelineStreamFrame.Heartbeat)

    override fun close() = Unit

    private fun listAgents(): List<Agent> =
        store.listAgentsProjected(limit = 10_000, offset = 0).decodeList(Agent.serializer())

    private fun <T> JsonArray?.decodeList(serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        this?.let { desktopChatJson.decodeFromJsonElement(ListSerializer(serializer), it) }.orEmpty()
}
