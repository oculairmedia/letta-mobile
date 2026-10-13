package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.testutil.TestMessageSource
import com.letta.mobile.data.api.NoActiveRunException
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.stream.SseFrame
import com.letta.mobile.data.stream.SseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MessageApiTimelineTransport(
    private val messageApi: TestMessageSource,
) : TimelineTransport {
    override suspend fun sendConversationMessage(
        conversationId: ConversationId,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = translateTimelineTransportErrors {
        SseParser.parse(messageApi.sendConversationMessage(conversationId, request))
    }

    override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> =
        translateTimelineTransportErrors {
        SseParser.parseFrames(messageApi.streamConversation(conversationId))
            .map { it.toTimelineStreamFrame() }
        }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = translateTimelineTransportErrors {
        messageApi.listConversationMessages(
            conversationId = conversationId,
            limit = limit,
            after = after,
            order = order,
        )
    }

    override suspend fun listConversationMessagePage(
        request: TimelineRemotePageRequest,
        progress: TimelinePageProgress?,
    ): TimelineRemotePageResult {
        val page = TimelineRemotePageAdapter.load(request) { limit, before, after, order ->
            translateTimelineTransportErrors {
                when {
                    before != null -> messageApi.fetchRecentMessages(
                        conversationId = ConversationId(request.scope.conversationId),
                        messageLimit = limit,
                        beforeMessageId = before,
                    )
                    else -> messageApi.listConversationMessages(
                        conversationId = ConversationId(request.scope.conversationId),
                        limit = limit,
                        after = after,
                        order = order,
                    )
                }
            }
        }
        return progress?.let { TimelineRemotePageProgressClassifier.classify(request, page, it) } ?: page
    }

    override suspend fun listAgentMessages(
        agentId: AgentId,
        limit: Int?,
        order: String?,
        conversationId: ConversationId?,
    ): List<LettaMessage> = translateTimelineTransportErrors {
        messageApi.listMessages(
            agentId = agentId,
            limit = limit,
            before = null,
            after = null,
            order = order,
            conversationId = conversationId?.value?.let(::ConversationId),
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
