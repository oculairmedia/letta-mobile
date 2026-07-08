package com.letta.mobile.data.timeline

import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.util.Telemetry
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Per-conversation [TimelineSyncLoop] registry.
 *
 * A single instance is shared across the app. Conversation
 * timelines are cached so that navigating away and back preserves state,
 * pending sends, and live cursors.
 *
 * This is the single source of truth for conversation message state.
 */
open class TimelineRepository(
    private val timelineTransport: TimelineTransport,
    private val pendingLocalStore: PendingLocalStore,
    private val conversationCursorStore: ConversationCursorStore,
) : TimelineExternalTransportWriter, BackendScopedCache {
    constructor(
        timelineTransport: TimelineTransport,
        pendingLocalStore: PendingLocalStore,
        maxCachedLoops: Int,
    ) : this(timelineTransport, pendingLocalStore, NoOpConversationCursorStore) {
        require(maxCachedLoops > 0) { "maxCachedLoops must be positive" }
        this.maxCachedLoops = maxCachedLoops
    }

    // Dedicated supervisor scope — child jobs fail in isolation.
    private val scope = CoroutineScope(SupervisorJob() + timelineIoDispatcher)

    private var maxCachedLoops = DEFAULT_MAX_CACHED_LOOPS

    // LRU registry. Kotlin common has no access-order LinkedHashMap
    // constructor (JVM-only), so we keep an insertion-ordered map and
    // emulate access order manually: getLoopLocked() re-inserts on hit so
    // the eldest entry is always first. Every access goes through
    // [loopsMutex], which makes the remove+reinsert touch safe.
    private val loops = LinkedHashMap<TimelineCacheKey, TimelineSyncLoop>()
    private val loopsMutex = Mutex()

    /** Mutex-guarded LRU get: touches the entry so eviction stays correct. */
    private fun getLoopLocked(key: TimelineCacheKey): TimelineSyncLoop? {
        val loop = loops.remove(key) ?: return null
        loops[key] = loop
        return loop
    }

    /**
     * Resolve all callers for a conversation to one authoritative loop. Legacy
     * UI paths may observe with agentId=null while Iroh writers ingest with a
     * scoped agentId; choosing by exact key (or singleOrNull aliasing) can split
     * those paths. Instead, deterministically canonicalize by conversationId and
     * retain the most specific key requested so future callers hit the same loop.
     */
    private fun getConversationLoopLocked(key: TimelineCacheKey): TimelineSyncLoop? {
        val matches = loops.entries.filter { it.key.conversationId == key.conversationId }
        if (matches.isEmpty()) return null
        val preferred = matches.firstOrNull { it.key == key }
            ?: matches.firstOrNull { it.key.agentId == key.agentId }
            ?: matches.firstOrNull { it.key.agentId != null }
            ?: matches.first()
        val loop = preferred.value
        var duplicateCount = 0
        matches.forEach { (existingKey, existingLoop) ->
            loops.remove(existingKey)
            if (existingLoop !== loop) {
                duplicateCount++
                existingLoop.close()
            }
        }
        val canonicalKey = when {
            key.agentId != null -> key
            preferred.key.agentId != null -> preferred.key
            else -> key
        }
        loops[canonicalKey] = loop
        Telemetry.event(
            "TimelineRepo", "loop.aliasResolved",
            "requestedAgentId" to key.agentId.orEmpty(),
            "canonicalAgentId" to canonicalKey.agentId.orEmpty(),
            "conversationId" to key.conversationId,
            "matchedLoopCount" to matches.size,
            "closedDuplicateLoopCount" to duplicateCount,
            level = if (matches.size > 1) Telemetry.Level.WARN else Telemetry.Level.INFO,
        )
        return loop
    }

    private fun removeConversationLoopLocked(key: TimelineCacheKey): TimelineSyncLoop? {
        val matches = loops.entries.filter { it.key.conversationId == key.conversationId }
        if (matches.isEmpty()) return null
        val preferred = matches.firstOrNull { it.key == key }
            ?: matches.firstOrNull { it.key.agentId == key.agentId }
            ?: matches.firstOrNull { it.key.agentId != null }
            ?: matches.first()
        matches.forEach { loops.remove(it.key) }
        return preferred.value
    }

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
    suspend fun getOrCreate(conversationId: String): TimelineSyncLoop = getOrCreate(agentId = null, conversationId = conversationId)

    suspend fun getOrCreate(agentId: String?, conversationId: String): TimelineSyncLoop {
        val key = TimelineCacheKey(agentId = agentId, conversationId = conversationId)
        // Fast path for already-cached loops. The access-order map mutates on
        // reads, so even cache hits go through the mutex.
        loopsMutex.withLock { getConversationLoopLocked(key) }?.let { cached ->
            Telemetry.event(
                "TimelineRepo", "getOrCreate.cacheHit",
                "agentId" to agentId.orEmpty(),
                "conversationId" to conversationId,
                "hydrated" to cached.hasHydratedSuccessfully,
            )
            if (!cached.hasHydratedSuccessfully) {
                runCatching {
                    withContext(timelineIoDispatcher) {
                        cached.hydrate()
                    }
                }.onFailure { t ->
                    Telemetry.error(
                        "TimelineRepo", "hydrate.retryFailed", t,
                        "agentId" to agentId.orEmpty(),
                        "conversationId" to conversationId,
                    )
                    runCatching { cached.emitHydrateFailed(t.message ?: "unknown") }
                }
            }
            return cached
        }
        val loop = getOrCreateLoopWithoutHydrate(key)
        // Hydrate OUTSIDE the mutex so parallel callers don't block each other.
        // If two callers race on the same conversationId, the second will find
        // the loop in the map and short-circuit at the fast path — hydrate
        // still only runs once per conv (first caller wins). The TimelineSync
        // writeMutex inside hydrate() prevents concurrent state mutation.
        runCatching {
            withContext(timelineIoDispatcher) {
                loop.hydrate()
            }
        }.onFailure { t ->
            Telemetry.error(
                "TimelineRepo", "hydrate.failed", t,
                "agentId" to agentId.orEmpty(),
                "conversationId" to conversationId,
            )
            runCatching { loop.emitHydrateFailed(t.message ?: "unknown") }
        }
        return loop
    }

    private suspend fun getOrCreateLoopWithoutHydrate(key: TimelineCacheKey): TimelineSyncLoop =
        // Mutex protects the map-insert critical section only (not hydrate).
        // Hydrate used to run inside the mutex which serialized all concurrent
        // warmup calls — an observed cause of "oldish state": conv-1598043a
        // wasn't hydrated until ~15s after app start because earlier slots in
        // the warmup list each held the lock for ~500ms. letta-mobile-mge5.
        loopsMutex.withLock {
            getConversationLoopLocked(key)?.let { return@withLock it }
            Telemetry.event(
                "TimelineRepo", "getOrCreate.cacheMiss",
                "agentId" to key.agentId.orEmpty(),
                "conversationId" to key.conversationId,
            )
            val created = TimelineSyncLoop(
                messageApi = timelineTransport,
                conversationId = key.conversationId,
                scope = scope,
                ingestedListenerProvider = { ingestedListener },
                pendingLocalStore = pendingLocalStore,
                conversationCursorStore = conversationCursorStore,
            )
            loops[key] = created
            evictEldestLoopsIfNeededLocked()
            created
        }

    /**
     * Number of cached sync loops currently owned by the singleton registry.
     *
     * Used for persistent-stream budget telemetry only. The repository does
     * not evict loops as part of budget enforcement; foreground conversations
     * still create on demand and remain cached for the process lifetime.
     */
    suspend fun cachedLoopCount(): Int = loopsMutex.withLock { loops.size }

    suspend fun clearAll() = loopsMutex.withLock {
        val count = loops.size
        loops.values.forEach { it.close() }
        loops.clear()
        Telemetry.event("TimelineRepo", "clearAll", "clearedLoopCount" to count)
    }

    override suspend fun clearForBackendSwitch() = clearAll()

    /** Observe a conversation's timeline state. */
    suspend fun observe(conversationId: String): StateFlow<Timeline> =
        getOrCreate(conversationId).state

    suspend fun observe(agentId: String?, conversationId: String): StateFlow<Timeline> =
        getOrCreate(agentId, conversationId).state

    /** Send a user message. Returns the client-generated otid. */
    suspend fun sendMessage(conversationId: String, content: String): String =
        getOrCreate(conversationId).send(content)

    suspend fun sendMessage(agentId: String?, conversationId: String, content: String): String =
        getOrCreate(agentId, conversationId).send(content)

    /**
     * Send a user message with attached images. The text body may be blank if
     * the user sends images only.
     */
    suspend fun sendMessage(
        conversationId: String,
        content: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>,
    ): String = getOrCreate(conversationId).send(content, attachments)

    suspend fun sendMessage(
        agentId: String?,
        conversationId: String,
        content: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>,
    ): String = getOrCreate(agentId, conversationId).send(content, attachments)

    /** Retry a failed send. */
    suspend fun retry(conversationId: String, otid: String) {
        getOrCreate(conversationId).retry(otid)
    }

    /**
     * Append an optimistic user bubble for a non-REST transport that supports
     * a caller-supplied otid. The admin-shim mobile WS echoes this otid back on
     * assistant frames and stamps it to disk for strict otid reconciliation,
     * so this path uses the standard LETTA_SERVER source.
     */
    override suspend fun appendExternalTransportLocal(
        conversationId: String,
        content: String,
        otid: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>,
    ): String = getOrCreate(conversationId).appendExternalTransportLocal(content, otid, attachments)

    override suspend fun appendExternalTransportLocal(
        agentId: String?,
        conversationId: String,
        content: String,
        otid: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>,
    ): String = getOrCreate(agentId, conversationId).appendExternalTransportLocal(content, otid, attachments)

    suspend fun appendExternalTransportLocal(
        conversationId: String,
        content: String,
        otid: String,
    ): String = appendExternalTransportLocal(conversationId, content, otid, emptyList())

    suspend fun appendExternalTransportLocal(
        agentId: String?,
        conversationId: String,
        content: String,
        otid: String,
    ): String = appendExternalTransportLocal(agentId, conversationId, content, otid, emptyList())

    /** Ingest a LettaMessage projected from an external live transport. */
    override suspend fun ingestExternalTransportMessage(
        conversationId: String,
        message: com.letta.mobile.data.model.LettaMessage,
    ) {
        getOrCreate(conversationId).ingestStreamEvent(message)
    }

    override suspend fun ingestExternalTransportMessage(
        agentId: String?,
        conversationId: String,
        message: com.letta.mobile.data.model.LettaMessage,
    ) {
        com.letta.mobile.util.Telemetry.event(
            "IrohGate", "gate4.repositoryIngest",
            "agentId" to agentId,
            "conversationId" to conversationId,
            "messageId" to message.id,
            "messageType" to message.messageType,
        )
        getOrCreate(agentId, conversationId).ingestStreamEvent(message)
    }

    /**
     * letta-mobile-fe51r (P2b pointer diet): resolve a projected tool-return
     * preview to its full body via the transport's on-demand fetch. Called
     * when the user expands a truncated tool card.
     */
    suspend fun resolveTruncatedToolReturn(
        agentId: String?,
        conversationId: String,
        messageId: String,
    ): Boolean = getOrCreate(agentId, conversationId).resolveTruncatedToolReturn(messageId)

    suspend fun postHandlerCollapse(conversationId: String) {
        val key = TimelineCacheKey(null, conversationId)
        val loop = loopsMutex.withLock { getConversationLoopLocked(key) }
        loop?.postHandlerCollapse()
    }

    suspend fun postHandlerCollapse(agentId: String?, conversationId: String) {
        val key = TimelineCacheKey(agentId, conversationId)
        val loop = loopsMutex.withLock { getConversationLoopLocked(key) }
        loop?.postHandlerCollapse()
    }

    /**
     * Pull recent server messages into an existing or newly-created timeline loop.
     *
     * Normal callers leave [forceRefresh] false so a healthy live stream remains
     * the single writer. User-initiated refresh/repair flows may set it true.
     */
    suspend fun reconcileRecentMessages(
        conversationId: String,
        reason: String,
        forceRefresh: Boolean = false,
    ): Int {
        return getOrCreate(conversationId).reconcileRecentMessages(reason, forceRefresh)
    }

    override suspend fun reconcileRecentMessages(
        agentId: String?,
        conversationId: String,
        reason: String,
        forceRefresh: Boolean,
    ): Int {
        return getOrCreate(agentId, conversationId).reconcileRecentMessages(reason, forceRefresh)
    }

    /**
     * letta-mobile-9hcg: flip the external-transport user-bubble Local
     * to SENT. Called from WsChatSendCoordinator on every TurnDone so
     * the Local appended via [appendExternalTransportLocal] doesn't sit
     * in SENDING state past the turn — which would otherwise keep
     * ChatTimelineObserver's `isStreaming` gate latched and flap the
     * typing indicator on subsequent timeline emits.
     */
    override suspend fun markExternalTransportLocalSent(conversationId: String, otid: String) {
        getOrCreate(conversationId).markExternalTransportLocalSent(otid)
    }

    override suspend fun markExternalTransportLocalSent(agentId: String?, conversationId: String, otid: String) {
        getOrCreate(agentId, conversationId).markExternalTransportLocalSent(otid)
    }

    /** Mark an externally-queued optimistic user bubble as failed before it was dispatched. */
    override suspend fun markExternalTransportLocalFailed(conversationId: String, otid: String) {
        getOrCreate(conversationId).markExternalTransportLocalFailed(otid)
    }

    override suspend fun markExternalTransportLocalFailed(agentId: String?, conversationId: String, otid: String) {
        getOrCreate(agentId, conversationId).markExternalTransportLocalFailed(otid)
    }

    /**
     * Signal that the external transport (WS) turn has completed for this
     * conversation. Clears the SSE-suppression flag so the persistent SSE
     * stream subscriber resumes ingesting messages for idle-period coverage.
     */
    override suspend fun clearExternalTransportActive(conversationId: String) {
        val key = TimelineCacheKey(null, conversationId)
        loopsMutex.withLock { getConversationLoopLocked(key) }?.clearExternalTransportActive()
    }

    override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) {
        val key = TimelineCacheKey(agentId, conversationId)
        loopsMutex.withLock { getConversationLoopLocked(key) }?.clearExternalTransportActive()
    }

    override suspend fun cleanupAbandonedAssistantFragments(
        agentId: String?,
        conversationId: String,
        runId: String?,
        turnId: String?,
        reason: String,
        candidateRunIds: Set<String>,
    ): Int {
        val key = TimelineCacheKey(agentId, conversationId)
        val loop = loopsMutex.withLock { getConversationLoopLocked(key) } ?: return 0
        return loop.cleanupAbandonedAssistantFragments(runId, turnId, reason, candidateRunIds)
    }

    /**
     * Reconcile a send that went through the admin-shim mobile WebSocket.
     * The shim guarantees `turn_done` is emitted after disk stamping, so callers
     * can invoke this immediately when that frame lands.
     */
    override suspend fun reconcileExternalTransportSend(
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

    override suspend fun reconcileExternalTransportSendScoped(
        agentId: String?,
        conversationId: String,
        externalConversationId: String,
        otid: String,
    ) {
        getOrCreate(agentId, conversationId).reconcileExternalTransportSend(
            agentId = agentId.orEmpty(),
            externalConversationId = externalConversationId,
            otid = otid,
        )
    }

    override suspend fun repairExpiredConversationCursor(conversationId: String, fallbackSeq: Long?) {
        repairExpiredConversationCursorScoped(agentId = null, conversationId = conversationId, fallbackSeq = fallbackSeq)
    }

    override suspend fun repairExpiredConversationCursorScoped(
        agentId: String?,
        conversationId: String,
        fallbackSeq: Long?,
    ) {
        conversationCursorStore.clearCursor(conversationId)
        val loop = getOrCreateLoopWithoutHydrate(TimelineCacheKey(agentId, conversationId))
        runCatching {
            withContext(timelineIoDispatcher) {
                loop.hydrate(
                    limit = CURSOR_REPAIR_HYDRATE_LIMIT,
                    recordConversationCursor = true,
                    fallbackCursorSeq = fallbackSeq,
                )
            }
        }.onSuccess {
            Telemetry.event(
                "TimelineRepo", "cursorExpired.repaired",
                "agentId" to agentId.orEmpty(),
                "conversationId" to conversationId,
                "fallbackSeq" to (fallbackSeq ?: -1L),
            )
        }.onFailure { t ->
            Telemetry.error(
                "TimelineRepo", "cursorExpired.repairFailed", t,
                "agentId" to agentId.orEmpty(),
                "conversationId" to conversationId,
                "fallbackSeq" to (fallbackSeq ?: -1L),
            )
            throw t
        }
    }

    /** Force a reload — clears the cached loop for the conversation. */
    suspend fun clear(conversationId: String) = loopsMutex.withLock {
        val key = TimelineCacheKey(null, conversationId)
        removeConversationLoopLocked(key)?.let { loop ->
            loop.close()
            Telemetry.event(
                "TimelineRepo", "loop.cleared",
                "conversationId" to conversationId,
            )
        }
    }

    suspend fun clear(agentId: String?, conversationId: String) = loopsMutex.withLock {
        val key = TimelineCacheKey(agentId, conversationId)
        removeConversationLoopLocked(key)?.let { loop ->
            loop.close()
            Telemetry.event(
                "TimelineRepo", "loop.cleared",
                "agentId" to agentId.orEmpty(),
                "conversationId" to conversationId,
            )
        }
    }

    private fun evictEldestLoopsIfNeededLocked() {
        while (loops.size > maxCachedLoops) {
            val eldestKey = loops.entries.firstOrNull()?.key ?: return
            loops.remove(eldestKey)?.let { loop ->
                loop.close()
                Telemetry.event(
                    "TimelineRepo", "loop.evicted",
                    "agentId" to eldestKey.agentId.orEmpty(),
                    "conversationId" to eldestKey.conversationId,
                    "cachedLoopCount" to loops.size,
                    "maxCachedLoops" to maxCachedLoops,
                )
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_CACHED_LOOPS = 32
        const val CURSOR_REPAIR_HYDRATE_LIMIT = 100
    }

    private data class TimelineCacheKey(
        val agentId: String?,
        val conversationId: String,
    )
}
