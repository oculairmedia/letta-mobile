package com.letta.mobile.desktop.chat

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame

/**
 * Hybrid desktop chat gateway that routes operations through App Server controller
 * OR HTTP gateway depending on current App Server capabilities.
 *
 * ROUTING STRATEGY:
 * - send/stream: Future controller-backed implementation (currently delegates to HTTP)
 * - conversation/message listing: HTTP gateway (App Server doesn't expose these yet)
 * - agent CRUD, model catalog: HTTP gateway
 *
 * This hybrid approach allows progressive migration: as the App Server gains API
 * coverage, individual methods can switch from HTTP delegation to controller routing
 * without changing the gateway contract or callsites.
 *
 * LIFECYCLE:
 * - [close] tears down both the controller and HTTP gateway
 * - The controller scope is owned by the factory and survives gateway close
 *   (allows multiple gateways to share one controller if needed)
 */
class DesktopHybridAppServerChatGateway(
    @Suppress("UNUSED_PARAMETER") // Will be used for send/stream in follow-up
    private val controller: AppServerController,
    private val httpGateway: DesktopLettaHttpChatGateway,
    private val onClose: () -> Unit = {},
) : DesktopChatGateway, AutoCloseable {

    // ========================================================================
    // Send/stream operations
    // These WILL route through the controller once the mapping is implemented.
    // For now, delegate to HTTP to keep the gateway functional.
    // ========================================================================

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): kotlinx.coroutines.flow.Flow<LettaMessage> {
        // TODO(xa2xc.9-follow-up): Route through controller.runTurn
        // Requires mapping MessageCreateRequest -> TurnCommand and
        // RuntimeEventDraft -> LettaMessage
        return httpGateway.sendConversationMessage(conversationId, request)
    }

    override suspend fun streamConversation(
        conversationId: String,
    ): kotlinx.coroutines.flow.Flow<TimelineStreamFrame> {
        // TODO(xa2xc.9-follow-up): Route through controller event subscription
        return httpGateway.streamConversation(conversationId)
    }

    // ========================================================================
    // Conversation/message listing operations
    // These delegate to HTTP because App Server doesn't expose listing APIs yet.
    // ========================================================================

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = httpGateway.listConversationMessages(conversationId, limit, after, order)

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = httpGateway.listAgentMessages(agentId, limit, order, conversationId)

    override suspend fun listConversations(
        limit: Int,
        archiveStatus: String?,
    ): List<Conversation> = httpGateway.listConversations(limit, archiveStatus)

    override suspend fun getConversation(
        conversationId: String,
    ): Conversation = httpGateway.getConversation(conversationId)

    // ========================================================================
    // Conversation management operations
    // These delegate to HTTP (App Server manages runtime state, not conversation CRUD).
    // ========================================================================

    override suspend fun deleteConversation(conversationId: String) {
        httpGateway.deleteConversation(conversationId)
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    override fun close() {
        httpGateway.close()
        onClose()
    }
}
