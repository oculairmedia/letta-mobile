package com.letta.mobile.data.repository.api

import com.letta.mobile.data.backend.AppServerRouteUnavailable
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.chat.branch.ConversationForkRequest
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationCreateParams
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationUpdateParams

/**
 * Conversation reads and writes a non-App-Server backend would serve, beyond the list read of
 * [ConversationRemoteSource]. letta-mobile-ckmub.4: this is the seam for one; there is no production
 * implementation since the Letta REST backend was removed, so
 * [com.letta.mobile.data.repository.ConversationRepository] serves conversations from the
 * on-device runtime or an Iroh host and binds no route here. Every call fails unless overridden.
 */
interface ConversationCrudRemoteSource : ConversationRemoteSource {
    suspend fun getConversation(conversationId: ConversationId): Conversation = noRoute("getConversation")

    suspend fun getConversation(conversationId: String): Conversation = getConversation(ConversationId(conversationId))

    suspend fun createConversation(params: ConversationCreateParams): Conversation = noRoute("createConversation")

    suspend fun updateConversation(conversationId: ConversationId, params: ConversationUpdateParams): Conversation =
        noRoute("updateConversation")

    suspend fun updateConversation(conversationId: String, params: ConversationUpdateParams): Conversation =
        updateConversation(ConversationId(conversationId), params)

    suspend fun deleteConversation(conversationId: ConversationId): Unit = noRoute("deleteConversation")

    suspend fun deleteConversation(conversationId: String): Unit = deleteConversation(ConversationId(conversationId))

    suspend fun forkConversation(request: ConversationForkRequest): Conversation = noRoute("forkConversation")

    suspend fun cancelConversation(conversationId: ConversationId, agentId: AgentId? = null): Unit =
        noRoute("cancelConversation")

    suspend fun recompileConversation(
        conversationId: ConversationId,
        dryRun: Boolean = false,
        agentId: AgentId? = null,
    ): String = noRoute("recompileConversation")
}

private fun noRoute(operation: String): Nothing =
    throw AppServerRouteUnavailable(SessionRoute.CONVERSATIONS, "ConversationCrudRemoteSource.$operation")
