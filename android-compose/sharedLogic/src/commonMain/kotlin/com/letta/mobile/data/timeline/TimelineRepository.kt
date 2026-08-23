package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.session.BackendScopedCache
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.util.Telemetry
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun externalConversationDedupeKey(agentId: String?, conversationId: String): String =
    "${agentId.orEmpty()}|$conversationId"

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
    private val externalSeenMutex = Mutex()
    private val externalSeenByConversation = LinkedHashMap<String, LinkedHashSet<String>>()

    /** Mutex-guarded LRU get: touches the entry so eviction stays correct. */
    private fun getLoopLocked(key: TimelineCacheKey): TimelineSyncLoop? {
        val loop = loops.remove(key) ?: return null
        loops[key] = loop
        return loop
    }

    /**
     * Legacy callers sometimes only know [TimelineCacheKey.conversationId],
     * while live Iroh writers use an agent-scoped key. Alias those paths only
     * inside a compatible scope: same agent, or one unscoped side. Once an
     * unscoped loop is claimed by a scoped agent, it is promoted to that agent
     * and must not be reused by a different scoped agent with the same bare
     * conversation id.
     */
    private fun getAliasedLoopLocked(key: TimelineCacheKey): TimelineSyncLoop? {
        val candidates = loops.entries.filter { it.key.conversationId == key.conversationId }
        if (candidates.isEmpty()) return null
        val compatible = candidates.filter { canAlias(it.key, key) }
        candidates.filterNot { canAlias(it.key, key) }.forEach { (existingKey, _) ->
            emitAliasRefused(existingKey, key)
        }
        val match = compatible.singleOrNull() ?: return null
        val existingKey = match.key
        val loop = loops.remove(existingKey) ?: return null
        val promotedKey = if (existingKey.agentId == null && key.agentId != null) key else existingKey
        loops[promotedKey] = loop
        Telemetry.event(
            "TimelineRepo", "loop.aliasResolved",
            "requestedAgentId" to key.agentId.orEmpty(),
            "canonicalAgentId" to promotedKey.agentId.orEmpty(),
            "conversationId" to key.conversationId,
        )
        return loop
    }

    private fun removeAliasedLoopLocked(key: TimelineCacheKey): TimelineSyncLoop? {
        val candidates = loops.entries.filter { it.key.conversationId == key.conversationId }
        val match = candidates.singleOrNull { canAlias(it.key, key) } ?: return null
        return loops.remove(match.key)
    }

    private fun canAlias(existing: TimelineCacheKey, requested: TimelineCacheKey): Boolean =
        existing.agentId == requested.agentId ||
            existing.agentId == null ||
            requested.agentId == null

    private fun emitAliasRefused(existing: TimelineCacheKey, requested: TimelineCacheKey) {
        if (existing.agentId == null || requested.agentId == null) return
        Telemetry.event(
            "TimelineRepo", "loop.aliasRefused",
            "existingAgentId" to existing.agentId,
            "requestedAgentId" to requested.agentId,
            "conversationId" to requested.conversationId,
            level = Telemetry.Level.WARN,
        )
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
        loopsMutex.withLock { getLoopLocked(key) ?: getAliasedLoopLocked(key) }?.let { cached ->
            Telemetry.event(
                "TimelineRepo", "getOrCreate.cacheHit",
                "agentId" to agentId.orEmpty(),
                "conversationId" to conversationId,
                "hydrated" to cached.hasHydratedSuccessfully,
            )
            if (!cached.hasHydratedSuccessfully) {
                hydrateSingleFlight(cached, key)
            }
            return cached
        }
        val loop = getOrCreateLoopWithoutHydrate(key)
        // Hydrate OUTSIDE the mutex so parallel callers don't block each other.
        // letta-mobile-oznnh: concurrent same-conversation callers now JOIN the
        // in-flight hydration instead of starting a duplicate one — the loop
        // is visible in the map before first hydration completes, so the old
        // fast-path retry raced with the first caller's still-running hydrate.
        hydrateSingleFlight(loop, key)
        return loop
    }

    /**
     * letta-mobile-oznnh: single-flight hydrate per conversation.
     *
     * The first caller for a conversation claims the flight and runs
     * [TimelineSyncLoop.hydrate]; concurrent callers for the SAME conversation
     * await that in-flight hydrate and share its outcome. Different
     * conversations get independent flights (keyed by conversationId, which is
     * also how alias resolution collapses loop identity). On failure the
     * flight is removed so a later caller can retry; joiners surface the same
     * failure to their own runCatching boundary without double-emitting the
     * hydrate-failed event (the owner emits once).
     */
    private val hydrateFlightsMutex = Mutex()

    /** conversationId -> in-flight hydration completion. Guarded by [hydrateFlightsMutex]. */
    private val hydrateFlights = LinkedHashMap<String, CompletableDeferred<Unit>>()

    private suspend fun hydrateSingleFlight(loop: TimelineSyncLoop, key: TimelineCacheKey) {
        val created = CompletableDeferred<Unit>()
        val joined = hydrateFlightsMutex.withLock {
            val existing = hydrateFlights[key.conversationId]
            if (existing != null) {
                existing
            } else {
                hydrateFlights[key.conversationId] = created
                null
            }
        }
        if (joined != null) {
            Telemetry.event(
                "TimelineRepo", "hydrate.joined",
                "agentId" to key.agentId.orEmpty(),
                "conversationId" to key.conversationId,
            )
            // Joiner swallows the shared outcome: the OWNER already emitted
            // HydrateFailed on the loop's event queue on failure — re-emitting
            // here would deliver duplicate events for one hydration attempt.
            runCatching { joined.await() }
                .onFailure { t ->
                    Telemetry.error(
                        "TimelineRepo", "hydrate.joinedFailed", t,
                        "agentId" to key.agentId.orEmpty(),
                        "conversationId" to key.conversationId,
                    )
                }
            return
        }
        try {
            withContext(timelineIoDispatcher) {
                loop.hydrate()
            }
            created.complete(Unit)
        } catch (t: Throwable) {
            // Remove the flight BEFORE completing so a waiter that retries
            // immediately isn't blocked by the dead flight.
            hydrateFlightsMutex.withLock {
                if (hydrateFlights[key.conversationId] === created) {
                    hydrateFlights.remove(key.conversationId)
                }
            }
            created.completeExceptionally(t)
            Telemetry.error(
                "TimelineRepo", "hydrate.failed", t,
                "agentId" to key.agentId.orEmpty(),
                "conversationId" to key.conversationId,
            )
            runCatching { loop.emitHydrateFailed(t.message ?: "unknown") }
        } finally {
            hydrateFlightsMutex.withLock {
                if (hydrateFlights[key.conversationId] === created) {
                    hydrateFlights.remove(key.conversationId)
                }
            }
        }
    }

    private suspend fun getOrCreateLoopWithoutHydrate(key: TimelineCacheKey): TimelineSyncLoop =
        // Mutex protects the map-insert critical section only (not hydrate).
        // Hydrate used to run inside the mutex which serialized all concurrent
        // warmup calls — an observed cause of "oldish state": conv-1598043a
        // wasn't hydrated until ~15s after app start because earlier slots in
        // the warmup list each held the lock for ~500ms. letta-mobile-mge5.
        loopsMutex.withLock {
            getLoopLocked(key)?.let { return@withLock it }
            getAliasedLoopLocked(key)?.let { return@withLock it }
            Telemetry.event(
                "TimelineRepo", "getOrCreate.cacheMiss",
                "agentId" to key.agentId.orEmpty(),
                "conversationId" to key.conversationId,
            )
            val created = TimelineSyncLoop(
                messageApi = timelineTransport,
                conversationId = key.conversationId,
                agentId = key.agentId,
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

    /**
     * letta-mobile-mxwtn: send with a pre-minted otid and NO Local append.
     * Pair with [appendOptimisticLocal] which the platform send coordinator
     * MUST call BEFORE this so the user bubble reaches the timeline state
     * synchronously. The transport call still runs and the MarkSent /
     * MarkFailed / reconcile flow runs on top of the existing Local event.
     */
    suspend fun sendWithOtid(
        agentId: String?,
        conversationId: String,
        content: String,
        otid: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image>,
    ) {
        getOrCreate(agentId, conversationId).sendWithOtid(otid, content, attachments)
    }

    /**
     * letta-mobile-mxwtn: synchronously insert an optimistic Local user
     * bubble into the timeline state. Returns `true` if the bubble was
     * appended, `false` if an event with that otid was already present
     * (idempotent — the existing one is kept so duplicate callers don't fork
     * the timeline). Use [sendWithOtid] to perform the transport call.
     */
    suspend fun appendOptimisticLocal(
        agentId: String?,
        conversationId: String,
        otid: String,
        content: String,
        attachments: List<com.letta.mobile.data.model.MessageContentPart.Image> = emptyList(),
    ): Boolean = getOrCreate(agentId, conversationId).appendOptimisticLocalSync(
        otid = otid,
        content = content,
        attachments = attachments,
    )

    /** letta-mobile-mxwtn: synchronous SENT transition on a Local bubble. */
    suspend fun markOptimisticLocalSent(
        agentId: String?,
        conversationId: String,
        otid: String,
    ) {
        getOrCreate(agentId, conversationId).markOptimisticLocalSentSync(otid)
    }

    /** letta-mobile-mxwtn: synchronous FAILED transition on a Local bubble. */
    suspend fun markOptimisticLocalFailed(
        agentId: String?,
        conversationId: String,
        otid: String,
    ) {
        getOrCreate(agentId, conversationId).markOptimisticLocalFailedSync(otid)
    }

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
        message: LettaMessage,
        source: String,
    ) {
        if (markExternalFrameDuplicate(null, conversationId, message, source)) return
        getOrCreate(conversationId).ingestStreamEvent(message, source)
    }

    override suspend fun ingestExternalTransportMessage(
        agentId: String?,
        conversationId: String,
        message: LettaMessage,
        source: String,
    ) {
        Telemetry.event(
            "IrohGate", "gate4.repositoryIngest",
            "agentId" to agentId,
            "conversationId" to conversationId,
            "messageId" to message.id,
            "messageType" to message.messageType,
        )
        if (markExternalFrameDuplicate(agentId, conversationId, message, source)) return
        getOrCreate(agentId, conversationId).ingestStreamEvent(message, source)
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
        val loop = loopsMutex.withLock { getLoopLocked(key) ?: getAliasedLoopLocked(key) }
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
        connectionGeneration: Long = 0L,
    ): RecentMessagesReconcileOutcome {
        return getOrCreate(conversationId).reconcileRecentMessages(reason, forceRefresh, connectionGeneration)
    }

    override suspend fun reconcileRecentMessages(
        agentId: String?,
        conversationId: String,
        reason: String,
        forceRefresh: Boolean,
        connectionGeneration: Long,
    ): RecentMessagesReconcileOutcome {
        return getOrCreate(agentId, conversationId).reconcileRecentMessages(reason, forceRefresh, connectionGeneration)
    }

    // letta-mobile-dangling-tool: forward turn-lifecycle signals to the
    // per-conversation loop so DanglingToolCallResolver knows when to
    // supersede a pending sweep (turnStarted) and when to (re)schedule one
    // (turnEnded — unconditionally, regardless of clean; see Codex #902
    // review finding 3 / DanglingToolCallResolver.scheduleSweepIfUnresolved).
    override suspend fun turnStarted(agentId: String?, conversationId: String) {
        getOrCreate(agentId, conversationId).turnStarted()
    }

    override suspend fun turnEnded(agentId: String?, conversationId: String, clean: Boolean) {
        getOrCreate(agentId, conversationId).turnEnded(clean)
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
        loopsMutex.withLock { getLoopLocked(key) ?: getAliasedLoopLocked(key) }?.clearExternalTransportActive()
    }

    override suspend fun clearExternalTransportActive(agentId: String?, conversationId: String) {
        val key = TimelineCacheKey(agentId, conversationId)
        loopsMutex.withLock { getLoopLocked(key) ?: getAliasedLoopLocked(key) }?.clearExternalTransportActive()
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
        val loop = loopsMutex.withLock { getLoopLocked(key) ?: getAliasedLoopLocked(key) } ?: return 0
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
        (loops.remove(key) ?: removeAliasedLoopLocked(key))?.let { loop ->
            loop.close()
            Telemetry.event(
                "TimelineRepo", "loop.cleared",
                "conversationId" to conversationId,
            )
        }
    }

    suspend fun clear(agentId: String?, conversationId: String) = loopsMutex.withLock {
        val key = TimelineCacheKey(agentId, conversationId)
        (loops.remove(key) ?: removeAliasedLoopLocked(key))?.let { loop ->
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

    private suspend fun markExternalFrameDuplicate(
        agentId: String?,
        conversationId: String,
        message: LettaMessage,
        source: String,
    ): Boolean {
        val key = externalFrameKey(message) ?: return false
        val scopedConversationKey = externalConversationDedupeKey(agentId, conversationId)
        val duplicate = externalSeenMutex.withLock {
            val keys = externalSeenByConversation.getOrPut(scopedConversationKey) { LinkedHashSet() }
            val added = keys.add(key)
            while (keys.size > MAX_SEEN_EXTERNAL_FRAMES_PER_CONVERSATION) {
                val oldest = keys.firstOrNull() ?: break
                keys.remove(oldest)
            }
            trimExternalSeenConversationCacheLocked()
            !added
        }
        if (duplicate) {
            Telemetry.event(
                "TimelineRepo", "externalFrame.exactDuplicateDropped",
                "conversationId" to conversationId,
                "messageId" to message.id,
                "messageType" to message.messageType,
                "seqId" to (message.seqId ?: -1),
                "source" to source,
            )
        }
        return duplicate
    }

    private fun trimExternalSeenConversationCacheLocked() {
        while (externalSeenByConversation.size > MAX_SEEN_EXTERNAL_CONVERSATIONS) {
            val oldest = externalSeenByConversation.keys.firstOrNull() ?: break
            externalSeenByConversation.remove(oldest)
        }
    }

    private fun externalFrameKey(message: LettaMessage): String? {
        // Only deduplicate frames with explicit sequence identity (seqId).
        // Forward incremental streaming deltas (no seqId) may legitimately
        // have identical content when streaming character-by-character and
        // must NOT be deduplicated based on content alone.
        val seqId = message.seqId
        if (seqId != null && seqId >= 0) {
            return "seq|$seqId|${message.messageType}|${message.id}"
        }
        // No seqId: this is a forward streaming delta. Do not deduplicate.
        return null
    }

    private companion object {
        const val DEFAULT_MAX_CACHED_LOOPS = 32
        const val CURSOR_REPAIR_HYDRATE_LIMIT = 100
        const val MAX_SEEN_EXTERNAL_FRAMES_PER_CONVERSATION = 512
        const val MAX_SEEN_EXTERNAL_CONVERSATIONS = 64
    }

    private data class TimelineCacheKey(
        val agentId: String?,
        val conversationId: String,
    )
}
