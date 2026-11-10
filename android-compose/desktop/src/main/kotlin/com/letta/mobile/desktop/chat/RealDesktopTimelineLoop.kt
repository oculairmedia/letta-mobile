package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.send.OutboundMessageCreate
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.data.timeline.TimelineTransport
import java.lang.AutoCloseable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * The timeline loop for backends that cannot serve the canonical ledger route (App Server, and
 * default-shim conversations). It keeps no durable copy: each selection hydrates from the backend.
 */
internal class RealDesktopTimelineLoop(
    gateway: DesktopChatGateway,
    conversation: DesktopConversationSummary,
    scope: CoroutineScope,
) : DesktopTimelineLoop, AutoCloseable {
    private val routing = resolveDesktopTimelineRouting(gateway, conversation)
    private val completions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val transport = object : TimelineTransport by routing.transport {
        override suspend fun sendConversationMessage(
            conversationId: ConversationId, request: MessageCreateRequest,
        ): Flow<LettaMessage> {
            val otid = OutboundMessageCreate.decode(request).otid
            return flow { emitAll(routing.transport.sendConversationMessage(conversationId, request)) }.onCompletion { cause ->
                otid?.let(completions::get)?.let { completion ->
                    if (cause == null) completion.complete(Unit) else completion.completeExceptionally(cause)
                }
            }
        }
    }
    private val delegate = TimelineSyncLoop(
        messageApi = transport,
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

    override suspend fun send(request: DesktopTimelineSendRequest): String {
        val otid = request.localMessageId?.value ?: return delegate.send(request.content.value, request.attachments)
        val completion = CompletableDeferred<Unit>()
        completions[otid] = completion
        try {
            delegate.appendOptimisticLocalSync(otid, request.content.value, request.attachments)
            delegate.sendWithOtid(otid, request.content.value, request.attachments)
            completion.await()
            return otid
        } finally {
            completions.remove(otid, completion)
        }
    }

    override fun close() {
        delegate.close()
    }

    override suspend fun closeAndAwait() {
        delegate.closeAndJoin()
    }
}
