package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import kotlinx.coroutines.flow.Flow

internal data class DefaultShimTransportIds(
    val agentId: AgentId,
    val externalConversationId: ConversationId,
)

internal class DefaultShimDesktopTimelineTransport(
    private val gateway: DesktopChatGateway,
    private val ids: DefaultShimTransportIds,
) : TimelineTransport {
    override suspend fun sendConversationMessage(
        conversationId: ConversationId,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> =
        gateway.sendConversationMessage(ids.externalConversationId, request)

    override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> =
        gateway.streamConversation(ids.externalConversationId)

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> =
        // Default-shim conversations hydrate from the agent message stream
        // (there is no real conversation id on the backend yet).
        listViaGateway(
            GatewayListParams(
                limit = limit?.let(::TimelinePageLimit),
                order = order?.let(::MessageListOrder),
                conversationId = null,
            ),
        )

    override suspend fun listAgentMessages(
        agentId: AgentId,
        limit: Int?,
        order: String?,
        conversationId: ConversationId?,
    ): List<LettaMessage> =
        listViaGateway(
            GatewayListParams(
                limit = limit?.let(::TimelinePageLimit),
                order = order?.let(::MessageListOrder),
                conversationId = conversationId,
            ),
        )

    private data class GatewayListParams(
        val limit: TimelinePageLimit?,
        val order: MessageListOrder?,
        val conversationId: ConversationId?,
    )

    private suspend fun listViaGateway(params: GatewayListParams): List<LettaMessage> =
        gateway.listAgentMessages(
            agentId = ids.agentId,
            limit = params.limit?.value,
            order = params.order?.value,
            conversationId = params.conversationId,
        )
}

