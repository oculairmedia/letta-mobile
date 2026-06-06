package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.api.NoActiveRunException
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.stream.SseFrame
import com.letta.mobile.data.stream.SseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class MessageApiTimelineTransport(
    private val messageApi: MessageApi,
) : TimelineTransport {
    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = translateTimelineTransportErrors {
        SseParser.parse(messageApi.sendConversationMessage(ConversationId(conversationId), request))
    }

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        translateTimelineTransportErrors {
        SseParser.parseFrames(messageApi.streamConversation(ConversationId(conversationId)))
            .map { it.toTimelineStreamFrame() }
        }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = translateTimelineTransportErrors {
        messageApi.listConversationMessages(
            conversationId = ConversationId(conversationId),
            limit = limit,
            after = after,
            order = order,
        )
    }

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = translateTimelineTransportErrors {
        messageApi.listMessages(
            agentId = AgentId(agentId),
            limit = limit,
            order = order,
            conversationId = conversationId?.let(::ConversationId),
        )
    }

    private fun SseFrame.toTimelineStreamFrame(): TimelineStreamFrame = when (this) {
        SseFrame.Heartbeat -> TimelineStreamFrame.Heartbeat
        SseFrame.Done -> TimelineStreamFrame.Done
        is SseFrame.Message -> TimelineStreamFrame.Message(message)
        is SseFrame.RawEvent -> TimelineStreamFrame.RawEvent(
            event = event,
            data = data,
            id = id,
        )
    }

    private inline fun <T> translateTimelineTransportErrors(block: () -> T): T =
        try {
            block()
        } catch (e: NoActiveRunException) {
            throw TimelineNoActiveRunException(e.conversationId)
        } catch (e: ApiException) {
            throw TimelineTransportHttpException(
                code = e.code,
                message = e.message ?: "HTTP ${e.code}",
                cause = e,
            )
        }
}
