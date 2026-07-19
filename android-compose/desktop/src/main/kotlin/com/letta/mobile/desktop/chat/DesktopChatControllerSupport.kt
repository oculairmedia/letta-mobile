package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.runtime.ChatComposerError
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ChatComposerPolicy
import com.letta.mobile.data.chat.runtime.ChatSessionReducer
import com.letta.mobile.data.chat.runtime.ChatStreamingPresence
import com.letta.mobile.data.chat.runtime.ChatStreamingPresencePolicy
import com.letta.mobile.data.chat.runtime.toChatConversationSummaries
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.ui.chat.render.ChatTimelineProjector
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.desktop.DesktopBootstrapState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val conversationId = conversation.id
    private val agentId = conversation.agentId
    private val transport = if (conversationId.isDefaultShimConversationId() && agentId != null) {
        DefaultShimDesktopTimelineTransport(
            gateway = gateway,
            agentId = agentId,
            externalConversationId = conversationId,
        )
    } else {
        gateway
    }
    private val loopConversationId = if (conversationId.isDefaultShimConversationId() && agentId != null) {
        "desktop-default-shim-$agentId-$conversationId"
    } else {
        conversationId
    }

    private val delegate = TimelineSyncLoop(
        messageApi = transport,
        conversationId = loopConversationId,
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

internal class DefaultShimDesktopTimelineTransport(
    private val gateway: DesktopChatGateway,
    private val agentId: String,
    private val externalConversationId: String,
) : TimelineTransport {
    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> =
        gateway.sendConversationMessage(externalConversationId, request)

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        gateway.streamConversation(externalConversationId)

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> =
        gateway.listAgentMessages(
            agentId = agentId,
            limit = limit,
            order = order,
            conversationId = null,
        )

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> =
        gateway.listAgentMessages(
            agentId = agentId,
            limit = limit,
            order = order,
            conversationId = conversationId,
        )
}

internal fun String.isDefaultShimConversationId(): Boolean =
    startsWith(DEFAULT_SHIM_CONVERSATION_PREFIX)

private const val DEFAULT_SHIM_CONVERSATION_PREFIX = "conv-default-"

/** Safety cap so the thinking indicator can't get stuck if no reply arrives. */
internal const val THINKING_TIMEOUT_MS = 180_000L
