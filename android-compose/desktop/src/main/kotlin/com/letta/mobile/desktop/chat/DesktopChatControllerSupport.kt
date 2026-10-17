package com.letta.mobile.desktop.chat

import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.chat.runtime.ChatComposerError
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.subagents.SubagentView
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.ui.chat.session.SubagentTimelineRoute
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
    suspend fun closeAndAwait() = close()
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

/**
 * The timeline loop for backends that cannot serve the canonical ledger route (App Server, and
 * default-shim conversations). It keeps no durable copy: each selection hydrates from the backend.
 */
internal class RealDesktopTimelineLoop(
    gateway: DesktopChatGateway,
    conversation: DesktopConversationSummary,
    scope: CoroutineScope,
) : DesktopTimelineLoop {
    private val routing = resolveDesktopTimelineRouting(gateway, conversation)
    private val delegate = TimelineSyncLoop(
        messageApi = routing.transport,
        conversationId = routing.loopConversationId.value,
        agentId = conversation.agentId,
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

    override suspend fun closeAndAwait() {
        delegate.closeAndJoin()
    }
}

@JvmInline
private value class DesktopChatLogTag(val value: String)

private val DESKTOP_CHAT_LOG_TAG = DesktopChatLogTag("DesktopChat")

internal fun String.isDefaultShimConversationId(): Boolean =
    startsWith(DEFAULT_SHIM_CONVERSATION_PREFIX.value)

@JvmInline
private value class ConversationIdPrefix(val value: String)

/**
 * What the canonical opener needs from the controller. The transport is resolved here, by the same
 * routing the in-memory loop uses, so the two can never disagree about which remote conversation a
 * scope names.
 */
data class DesktopCanonicalOpenRequest(
    val agentId: String,
    val conversationId: String,
    val transport: TimelineTransport,
    val scope: kotlinx.coroutines.CoroutineScope,
)

internal fun desktopTimelineTransportFor(
    gateway: DesktopChatGateway,
    conversation: DesktopConversationSummary,
): TimelineTransport = resolveDesktopTimelineRouting(gateway, conversation).transport

private data class DesktopTimelineRouting(
    val transport: TimelineTransport,
    val loopConversationId: ConversationId,
)

private fun resolveDesktopTimelineRouting(
    gateway: DesktopChatGateway,
    conversation: DesktopConversationSummary,
): DesktopTimelineRouting = resolveDesktopTimelineRouting(gateway, ConversationId(conversation.id), conversation.agentId?.let(::AgentId))

/**
 * letta-mobile-ulcg6.1: where a subagent's conversation is read for the dual view, routed the way a
 * selected conversation is (a `conv-default-<agentId>` child goes through the default-shim transport).
 */
internal fun desktopSubagentTimelineRoute(gateway: DesktopChatGateway, view: SubagentView): SubagentTimelineRoute {
    val routing = resolveDesktopTimelineRouting(gateway, view.conversationId, view.agentId)
    return SubagentTimelineRoute(routing.transport, routing.loopConversationId)
}

private fun resolveDesktopTimelineRouting(
    gateway: DesktopChatGateway,
    conversationId: ConversationId,
    agentId: AgentId?,
): DesktopTimelineRouting {
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
