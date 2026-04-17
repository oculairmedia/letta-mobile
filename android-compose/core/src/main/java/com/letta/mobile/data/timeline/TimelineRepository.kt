package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
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
 * This is the Timeline-sync replacement for [com.letta.mobile.data.repository.MessageRepository].
 * It runs alongside the legacy repository behind the `use_timeline_sync`
 * feature flag (see [com.letta.mobile.data.repository.SettingsRepository]).
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
            loops[conversationId]?.let { return it }
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
                android.util.Log.w(
                    "TimelineRepository",
                    "Hydrate failed for $conversationId — proceeding with empty timeline",
                    t,
                )
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

    /** Retry a failed send. */
    suspend fun retry(conversationId: String, otid: String) {
        getOrCreate(conversationId).retry(otid)
    }

    /** Force a reload — clears the cached loop for the conversation. */
    suspend fun clear(conversationId: String) = loopsMutex.withLock {
        loops.remove(conversationId)
    }
}
