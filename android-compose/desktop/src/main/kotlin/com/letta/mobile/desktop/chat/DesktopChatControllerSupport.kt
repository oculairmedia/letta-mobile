package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.runtime.ChatComposerError
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.desktop.data.DesktopConfirmedTimelineStore
import kotlinx.coroutines.CoroutineScope
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

internal data class DesktopTimelinePersistence(
    val store: ConfirmedTimelineStore = DesktopConfirmedTimelineStore(),
    val backendId: String = "desktop-local",
)

internal class RealDesktopTimelineLoop private constructor(
    gateway: DesktopChatGateway,
    conversation: DesktopConversationSummary,
    scope: CoroutineScope,
    confirmedTimelineStore: ConfirmedTimelineStore,
    backendId: String,
    initialTimeline: Timeline?,
    initialRevision: Long,
) : DesktopTimelineLoop {
    private val routing = resolveDesktopTimelineRouting(gateway, conversation)
    private val timelineScope = TimelineScope(
        backendId = backendId,
        conversationId = routing.loopConversationId.value,
        agentId = conversation.agentId,
    )
    private val delegate = TimelineSyncLoop(
        messageApi = routing.transport,
        conversationId = routing.loopConversationId.value,
        agentId = conversation.agentId,
        scope = scope,
        logTag = DESKTOP_CHAT_LOG_TAG.value,
        confirmedTimelineStore = confirmedTimelineStore,
        timelineScope = timelineScope,
        initialTimeline = initialTimeline,
        initialRevision = initialRevision,
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

    companion object {
        suspend fun create(
            gateway: DesktopChatGateway,
            conversation: DesktopConversationSummary,
            scope: CoroutineScope,
            persistence: DesktopTimelinePersistence = DesktopTimelinePersistence(),
        ): RealDesktopTimelineLoop {
            val routing = resolveDesktopTimelineRouting(gateway, conversation)
            val timelineScope = TimelineScope(persistence.backendId, routing.loopConversationId.value, conversation.agentId)
            val snapshot = runCatching { persistence.store.readSnapshot(timelineScope) }.getOrNull()
            return RealDesktopTimelineLoop(
                gateway, conversation, scope, persistence.store, persistence.backendId,
                snapshot?.let(TimelineSnapshotCodec::storedEnvelopeToTimeline), snapshot?.revision ?: 0L,
            )
        }
    }
}

@JvmInline
private value class DesktopChatLogTag(val value: String)

private val DESKTOP_CHAT_LOG_TAG = DesktopChatLogTag("DesktopChat")

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
