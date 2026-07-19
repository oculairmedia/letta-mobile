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
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> =
        gateway.sendConversationMessage(ids.externalConversationId.value, request)

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        gateway.streamConversation(ids.externalConversationId.value)

    override suspend fun listConversationMessages(
        conversationId: String,
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
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> =
        listViaGateway(
            GatewayListParams(
                limit = limit?.let(::TimelinePageLimit),
                order = order?.let(::MessageListOrder),
                conversationId = conversationId?.let(::ConversationId),
            ),
        )

    private data class GatewayListParams(
        val limit: TimelinePageLimit?,
        val order: MessageListOrder?,
        val conversationId: ConversationId?,
    )

    private suspend fun listViaGateway(params: GatewayListParams): List<LettaMessage> =
        gateway.listAgentMessages(
            agentId = ids.agentId.value,
            limit = params.limit?.value,
            order = params.order?.value,
            conversationId = params.conversationId?.value,
        )
}

