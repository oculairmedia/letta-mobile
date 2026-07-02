package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.coroutines.flow.Flow

class IrohRoutingTimelineTransport(
    private val settingsRepository: ISettingsRepository,
    private val http: TimelineTransport,
    private val iroh: TimelineTransport,
) : TimelineTransport {
    private fun selected(): TimelineTransport =
        if (IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)) iroh else http

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = selected().sendConversationMessage(conversationId, request)

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        selected().streamConversation(conversationId)

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = selected().listConversationMessages(conversationId, limit, after, order)

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = selected().listAgentMessages(agentId, limit, order, conversationId)
}
