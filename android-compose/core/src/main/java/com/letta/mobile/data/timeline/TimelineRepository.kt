package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.util.Telemetry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-conversation [TimelineSyncLoop] registry.
 *
 * A single instance is shared across the app (Hilt @Singleton). Conversation
 * timelines are cached so that navigating away and back preserves state,
 * pending sends, and live cursors.
 *
 * This is the single source of truth for conversation message state.
 * [com.letta.mobile.data.repository.MessageRepository] is retained only as a
 * stateless HTTP helper for older-message pagination, approvals, search,
 * batches, reset, and the conversation inspector.
 */
@Singleton
open class TimelineRepository @Inject constructor(
    private val messageApi: MessageApi,
) {
    // Dedicated supervisor scope — child jobs fail in isolation.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val loops = mutableMapOf<String, TimelineSyncLoop>()
    private val loopsMutex = Mutex()

    /**
     * Get or create the sync loop for the given conversation.
     *
     * The first call creates the loop and hydrates it from the server.
     * Subsequent calls return the cached loop without re-hydrating.
     */
    suspend fun getOrCreate(conversationId: String): TimelineSyncLoop {
        loopsMutex.withLock {
            loops[conversationId]?.let {
                Telemetry.event(
                    "TimelineRepo", "getOrCreate.cacheHit",
                    "conversationId" to conversationId,
                )
                return it
            }
            Telemetry.event(
                "TimelineRepo", "getOrCreate.cacheMiss",
                "conversationId" to conversationId,
            )
            val loop = TimelineSyncLoop(
                messageApi = messageApi,
                conversationId = conversationId,
                scope = scope,
            )
            loops[conversationId] = loop
            // Hydrate may fail (e.g. 404 on a brand-new conversation) — never
            // let that propagate up and kill the chat screen. The user can
            // still send messages; reconcile will fill in history afterwards.
            runCatching { loop.hydrate() }.onFailure { t ->
                Telemetry.error(
                    "TimelineRepo", "hydrate.failed", t,
                    "conversationId" to conversationId,
                )
                // Notify observers so they can clear loading state instead of
                // spinning forever waiting for a Hydrated event that never comes.
                runCatching { loop.emitHydrateFailed(t.message ?: "unknown") }
            }
            return loop
        }
    }

    /** Observe a conversation's timeline state. */
    suspend fun observe(conversationId: String): StateFlow<Timeline> =
        getOrCreate(conversationId).state

    /** Send a user message. Returns the client-generated otid. */
    suspend fun sendMessage(conversationId: String, content: String): String =
        getOrCreate(conversationId).send(content)

    /**
     * Send a user message with attached images. The text body may be blank if
     * the user sends images only.
     */
    suspend fun sendMessage(
        conversationId: String,
        content: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>,
    ): String = getOrCreate(conversationId).send(content, attachments)

    /** Retry a failed send. */
    suspend fun retry(conversationId: String, otid: String) {
        getOrCreate(conversationId).retry(otid)
    }

    /** Force a reload — clears the cached loop for the conversation. */
    suspend fun clear(conversationId: String) = loopsMutex.withLock {
        loops.remove(conversationId)
    }
}
