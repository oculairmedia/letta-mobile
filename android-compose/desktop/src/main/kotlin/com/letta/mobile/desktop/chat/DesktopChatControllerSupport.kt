package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.runtime.ChatComposerError
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

internal fun ChatComposerError.toDesktopMessage(limits: AttachmentLimits): String = when (this) {
    ChatComposerError.MaxAttachmentCountExceeded -> "Attach up to ${limits.maxAttachmentCount} images."
    ChatComposerError.MaxTotalBase64BytesExceeded -> "Attached images exceed the desktop payload limit."
    ChatComposerError.AttachmentLoadFailed -> "Could not attach image."
}

interface DesktopTimelineLoop {
    val state: StateFlow<Timeline>
    suspend fun hydrate(request: DesktopTimelineHydrateRequest = DesktopTimelineHydrateRequest())
    suspend fun send(request: DesktopTimelineSendRequest): String
    fun close()
}

data class DesktopTimelineHydrateRequest(
    val limit: TimelinePageLimit = TimelinePageLimit(50),
    val recordConversationCursor: Boolean = false,
)

data class DesktopTimelineSendRequest(
    val content: MessageBody,
    val attachments: List<MessageContentPart.Image> = emptyList(),
)

@JvmInline
value class MessageBody(val value: String)

@JvmInline
value class MessageListOrder(val value: String)

@JvmInline
value class TimelinePageLimit(val value: Int)

internal class RealDesktopTimelineLoop(
    gateway: DesktopChatGateway,
    conversation: DesktopConversationSummary,
    scope: CoroutineScope,
) : DesktopTimelineLoop {
    private val routing = resolveDesktopTimelineRouting(gateway, conversation)

    private val delegate = TimelineSyncLoop(
        messageApi = routing.transport,
        conversationId = routing.loopConversationId.value,
        scope = scope,
        logTag = DESKTOP_CHAT_LOG_TAG.value,
    )

    override val state: StateFlow<Timeline> = delegate.state

    override suspend fun hydrate(request: DesktopTimelineHydrateRequest) {
        delegate.hydrate(
            limit = request.limit.value,
            recordConversationCursor = request.recordConversationCursor,
        )
    }

    override suspend fun send(request: DesktopTimelineSendRequest): String =
        delegate.send(request.content.value, request.attachments)

    override fun close() {
        delegate.close()
    }
}

@JvmInline
private value class DesktopChatLogTag(val value: String)

private val DESKTOP_CHAT_LOG_TAG = DesktopChatLogTag("DesktopChat")

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

internal fun String.isDefaultShimConversationId(): Boolean =
    startsWith(DEFAULT_SHIM_CONVERSATION_PREFIX.value)

@JvmInline
private value class ConversationIdPrefix(val value: String)

private data class DesktopTimelineRouting(
    val transport: TimelineTransport,
    val loopConversationId: ConversationId,
)

private fun resolveDesktopTimelineRouting(
    gateway: DesktopChatGateway,
    conversation: DesktopConversationSummary,
): DesktopTimelineRouting {
    val conversationId = ConversationId(conversation.id)
    val agentId = conversation.agentId?.let(::AgentId)
    val usesDefaultShim = conversationId.value.isDefaultShimConversationId() && agentId != null
    return if (usesDefaultShim) {
        defaultShimRouting(gateway, conversationId, agentId!!)
    } else {
        DesktopTimelineRouting(transport = gateway, loopConversationId = conversationId)
    }
}

private fun defaultShimRouting(
    gateway: DesktopChatGateway,
    conversationId: ConversationId,
    agentId: AgentId,
): DesktopTimelineRouting =
    DesktopTimelineRouting(
        transport = DefaultShimDesktopTimelineTransport(
            gateway = gateway,
            ids = DefaultShimTransportIds(
                agentId = agentId,
                externalConversationId = conversationId,
            ),
        ),
        loopConversationId = ConversationId(
            "desktop-default-shim-${agentId.value}-${conversationId.value}",
        ),
    )

private val DEFAULT_SHIM_CONVERSATION_PREFIX = ConversationIdPrefix("conv-default-")

/** Safety cap so the thinking indicator can't get stuck if no reply arrives. */
internal const val THINKING_TIMEOUT_MS = 180_000L
