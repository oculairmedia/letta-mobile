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
    suspend fun hydrate(limit: Int = 50, recordConversationCursor: Boolean = false)
    suspend fun send(content: String, attachments: List<MessageContentPart.Image> = emptyList()): String
    fun close()
}

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
        logTag = "DesktopChat",
    )

    override val state: StateFlow<Timeline> = delegate.state

    override suspend fun hydrate(limit: Int, recordConversationCursor: Boolean) {
        delegate.hydrate(limit = limit, recordConversationCursor = recordConversationCursor)
    }

    override suspend fun send(
        content: String,
        attachments: List<MessageContentPart.Image>,
    ): String = delegate.send(content, attachments)

    override fun close() {
        delegate.close()
    }
}

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
        listViaGateway(GatewayListParams(limit = limit, order = order, conversationId = null))

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> =
        listViaGateway(GatewayListParams(limit = limit, order = order, conversationId = conversationId))

    private data class GatewayListParams(
        val limit: Int?,
        val order: String?,
        val conversationId: String?,
    )

    private suspend fun listViaGateway(params: GatewayListParams): List<LettaMessage> =
        gateway.listAgentMessages(
            agentId = ids.agentId.value,
            limit = params.limit,
            order = params.order,
            conversationId = params.conversationId,
        )
}

internal fun String.isDefaultShimConversationId(): Boolean =
    startsWith(DEFAULT_SHIM_CONVERSATION_PREFIX)

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
    val transport: TimelineTransport = if (usesDefaultShim) {
        DefaultShimDesktopTimelineTransport(
            gateway = gateway,
            ids = DefaultShimTransportIds(
                agentId = agentId!!,
                externalConversationId = conversationId,
            ),
        )
    } else {
        gateway
    }
    val loopConversationId = if (usesDefaultShim) {
        ConversationId("desktop-default-shim-${agentId!!.value}-${conversationId.value}")
    } else {
        conversationId
    }
    return DesktopTimelineRouting(transport = transport, loopConversationId = loopConversationId)
}

private const val DEFAULT_SHIM_CONVERSATION_PREFIX = "conv-default-"

/** Safety cap so the thinking indicator can't get stuck if no reply arrives. */
internal const val THINKING_TIMEOUT_MS = 180_000L
