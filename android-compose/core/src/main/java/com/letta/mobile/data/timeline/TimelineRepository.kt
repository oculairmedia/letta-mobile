package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.util.Telemetry
import java.util.LinkedHashMap
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
    private val pendingLocalStore: PendingLocalStore,
) {
    internal constructor(
        messageApi: MessageApi,
        pendingLocalStore: PendingLocalStore,
        maxCachedLoops: Int,
    ) : this(messageApi, pendingLocalStore) {
        require(maxCachedLoops > 0) { "maxCachedLoops must be positive" }
        this.maxCachedLoops = maxCachedLoops
    }

    // Dedicated supervisor scope — child jobs fail in isolation.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var maxCachedLoops = DEFAULT_MAX_CACHED_LOOPS
    private val loops = LinkedHashMap<String, TimelineSyncLoop>(16, 0.75f, true)
    private val loopsMutex = Mutex()

    /**
     * Listener the :app module can install to receive inbound-message events
     * from every TimelineSyncLoop we manage. Used to post system notifications
     * when messages arrive while the relevant chat isn't foregrounded.
     * See letta-mobile-mge5. Set once at application startup.
     */
    @Volatile
    var ingestedListener: IngestedMessageListener? = null

    /**
     * Get or create the sync loop for the given conversation.
     *
     * The first call creates the loop and hydrates it from the server.
     * Subsequent calls return the cached loop without re-hydrating.
     */
    suspend fun getOrCreate(conversationId: String): TimelineSyncLoop {
        // Fast path for already-cached loops. The access-order map mutates on
        // reads, so even cache hits go through the mutex.
        loopsMutex.withLock { loops[conversationId] }?.let {
            Telemetry.event(
                "TimelineRepo", "getOrCreate.cacheHit",
                "conversationId" to conversationId,
            )
            return it
        }
        // Mutex protects the map-insert critical section only (not hydrate).
        // Hydrate used to run inside the mutex which serialized all concurrent
        // warmup calls — an observed cause of "oldish state": conv-1598043a
        // wasn't hydrated until ~15s after app start because earlier slots in
        // the warmup list each held the lock for ~500ms. letta-mobile-mge5.
        val loop = loopsMutex.withLock {
            loops[conversationId]?.let { return@withLock it }
            Telemetry.event(
                "TimelineRepo", "getOrCreate.cacheMiss",
                "conversationId" to conversationId,
            )
            val created = TimelineSyncLoop(
                messageApi = messageApi,
                conversationId = conversationId,
                scope = scope,
                ingestedListenerProvider = { ingestedListener },
                pendingLocalStore = pendingLocalStore,
            )
            loops[conversationId] = created
            evictEldestLoopsIfNeededLocked()
            created
        }
        // Hydrate OUTSIDE the mutex so parallel callers don't block each other.
        // If two callers race on the same conversationId, the second will find
        // the loop in the map and short-circuit at the fast path — hydrate
        // still only runs once per conv (first caller wins). The TimelineSync
        // writeMutex inside hydrate() prevents concurrent state mutation.
        runCatching { loop.hydrate() }.onFailure { t ->
            Telemetry.error(
                "TimelineRepo", "hydrate.failed", t,
                "conversationId" to conversationId,
            )
            runCatching { loop.emitHydrateFailed(t.message ?: "unknown") }
        }
        return loop
    }

    /**
     * Number of cached sync loops currently owned by the singleton registry.
     *
     * Used for persistent-stream budget telemetry only. The repository does
     * not evict loops as part of budget enforcement; foreground conversations
     * still create on demand and remain cached for the process lifetime.
     */
    suspend fun cachedLoopCount(): Int = loopsMutex.withLock { loops.size }

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

    /**
     * letta-mobile-c87t: Append a Client-Mode optimistic Local for a user
     * message that's being sent through the LettaBot WS gateway rather than
     * the timeline send queue. Stamps a `cm-<uuid>` localId and tags the
     * event as [MessageSource.CLIENT_MODE_HARNESS]. Does NOT enqueue to the
     * outbound send queue — the gateway is responsible for delivery.
     *
     * Returns the generated localId so callers can correlate the event later
     * (telemetry, retries). Subsequent reconcile / live-stream paths will
     * fuzzy-collapse this Local against the Confirmed echo from Letta SSE
     * via [Timeline.collapseClientModeFuzzyMatch].
     */
    suspend fun appendClientModeLocal(
        conversationId: String,
        content: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image> = emptyList(),
    ): String = getOrCreate(conversationId).appendClientModeLocal(content, attachments)

    /**
     * Append an optimistic user bubble for a non-REST transport that supports
     * a caller-supplied otid. The admin-shim mobile WS echoes this otid back on
     * assistant frames and stamps it to disk for reconcile, so this path uses
     * the standard LETTA_SERVER source rather than the Client Mode fuzzy-match
     * source.
     */
    suspend fun appendExternalTransportLocal(
        conversationId: String,
        content: String,
        otid: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image> = emptyList(),
    ): String = getOrCreate(conversationId).appendExternalTransportLocal(content, otid, attachments)

    /** Ingest a LettaMessage projected from an external live transport. */
    suspend fun ingestExternalTransportMessage(
        conversationId: String,
        message: com.letta.mobile.data.model.LettaMessage,
    ) {
        getOrCreate(conversationId).ingestStreamEvent(message)
    }

    /**
     * letta-mobile-5s1n: upsert a Client Mode assistant-streaming Local.
     * See [TimelineSyncLoop.upsertClientModeLocalAssistantChunk] for full
     * contract.
     *
     * Caller-supplied [localId] should be stable across repeat chunks for
     * the same logical event:
     *  - Assistant text:  `cm-assist-<runId or stable id>`
     *  - Reasoning:       `cm-reason-<runId or stable id>`
     *  - Tool call:       `cm-tool-<toolCallId>`
     */
    suspend fun upsertClientModeLocalAssistantChunk(
        conversationId: String,
        localId: String,
        build: () -> TimelineEvent.Local,
        transform: (TimelineEvent.Local) -> TimelineEvent.Local,
    ): String = getOrCreate(conversationId).upsertClientModeLocalAssistantChunk(
        localId = localId,
        build = build,
        transform = transform,
    )

    /** Route a typed Client Mode stream chunk through the timeline reducer. */
    suspend fun upsertClientModeStreamChunk(
        conversationId: String,
        chunk: ClientModeStreamChunk,
        assistantMessageId: String,
    ): String? = getOrCreate(conversationId).upsertClientModeStreamChunk(
        chunk = chunk,
        assistantMessageId = assistantMessageId,
    )

    /**
     * letta-mobile-iuh6: Re-run fuzzy collapse across existing events after
     * a notification reply handler's WS stream completes. Absorbs any
     * CLIENT_MODE_HARNESS Locals that arrived after the initial SSE reconcile.
     */
    suspend fun postHandlerCollapse(conversationId: String) {
        val loop = loopsMutex.withLock { loops[conversationId] }
        loop?.postHandlerCollapse()
    }

    /** Pull recent server messages into an existing or newly-created timeline loop. */
    suspend fun reconcileRecentMessages(conversationId: String, reason: String) {
        getOrCreate(conversationId).reconcileRecentMessages(reason)
    }

    /**
     * Reconcile a send that went through the admin-shim mobile WebSocket.
     * The shim guarantees `turn_done` is emitted after disk stamping, so callers
     * can invoke this immediately when that frame lands.
     */
    suspend fun reconcileExternalTransportSend(
        conversationId: String,
        agentId: String,
        externalConversationId: String,
        otid: String,
    ) {
        getOrCreate(conversationId).reconcileExternalTransportSend(
            agentId = agentId,
            externalConversationId = externalConversationId,
            otid = otid,
        )
    }

    /** Force a reload — clears the cached loop for the conversation. */
    suspend fun clear(conversationId: String) = loopsMutex.withLock {
        loops.remove(conversationId)?.let { loop ->
            loop.close()
            Telemetry.event(
                "TimelineRepo", "loop.cleared",
                "conversationId" to conversationId,
            )
        }
    }

    private fun evictEldestLoopsIfNeededLocked() {
        while (loops.size > maxCachedLoops) {
            val eldestConversationId = loops.entries.firstOrNull()?.key ?: return
            loops.remove(eldestConversationId)?.let { loop ->
                loop.close()
                Telemetry.event(
                    "TimelineRepo", "loop.evicted",
                    "conversationId" to eldestConversationId,
                    "cachedLoopCount" to loops.size,
                    "maxCachedLoops" to maxCachedLoops,
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_CACHED_LOOPS = 32
    }
}
