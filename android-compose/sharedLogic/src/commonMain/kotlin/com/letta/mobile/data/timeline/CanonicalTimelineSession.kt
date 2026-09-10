package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.sync.withLock

/** Conversation ownership is independent of screen selection; hosts explicitly retire idle owners. */
class CanonicalTimelineCoordinator(
    private val store: TimelineBoundedStore,
    private val transport: TimelineTransport,
) {
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val owners = mutableMapOf<TimelineScope, Owner>()
    private var revoked = false

    /** Runtime generation retirement, unlike viewport disposal, invalidates every owner. */
    suspend fun revoke() = mutex.withLock {
        revoked = true
        owners.values.forEach { it.session.close(it.selection) }
        owners.clear()
    }

    class Owner internal constructor(
        val session: CanonicalTimelineSession,
        val selection: TimelineEngineSelection,
    ) {
        internal var liveFence: TimelineLiveFence? = null
        internal var activeRepairs: Int = 0
        internal val presentations = mutableSetOf<Presentation>()
    }

    class Presentation internal constructor(val owner: Owner, val anchor: TimelinePageKey?)

    suspend fun attach(owner: Owner, target: TimelineMessageId? = null): Presentation? = mutex.withLock {
        check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
        val anchor = target?.let { identity -> store.read(owner.selection.scope) { locate(identity) } }
        if (target != null && anchor == null) return@withLock null
        Presentation(owner, anchor).also { owner.presentations.add(it) }
    }

    /** Detaching a viewport never closes the conversation or its active transport. */
    suspend fun detach(presentation: Presentation) = mutex.withLock {
        val owner = presentation.owner
        owner.presentations.remove(presentation)
        releaseUnobservedSettlement(owner)
        Unit
    }

    suspend fun acquire(scope: TimelineScope): Owner = mutex.withLock {
        check(!revoked) { "Canonical runtime generation revoked" }
        owners[scope]?.let { return@withLock it }
        val session = CanonicalTimelineSession(store, transport, scope, enabled = true)
        val opened = session.open() as TimelineEngineOpen.Opened
        Owner(session, opened.selection).also { owners[scope] = it }
    }

    suspend fun current(scope: TimelineScope): Owner? = mutex.withLock { owners[scope] }

    suspend fun appendPending(owner: Owner, record: CanonicalPendingLocalStore.Record) = mutex.withLock {
        check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
        owner.session.appendPending(record)
    }

    suspend fun markPending(owner: Owner, otid: String, delivery: CanonicalPendingLocalStore.Delivery) = mutex.withLock {
        check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
        owner.session.markPending(otid, delivery)
    }

    suspend fun discardFailedPending(owner: Owner, otid: String): Boolean = mutex.withLock {
        check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
        owner.session.discardFailedPending(otid)
    }

    suspend fun reconcileRecent(owner: Owner): TimelineEnginePageOutcome = reconcileRecentDetailed(owner).outcome

    // The overlay is the only copy of a settled turn until this path writes it, so keep it resident;
    // the presentation drops it once the reconciled rows are on screen.
    suspend fun reconcileRecentDetailed(owner: Owner): TimelineEngineReconcileResult = withRepairLease(owner) {
        owner.session.reconcileRecentDetailed(owner.selection)
    }

    internal suspend fun retainRepair(owner: Owner) = mutex.withLock {
        check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
        check(owner.activeRepairs < Int.MAX_VALUE)
        owner.activeRepairs++
    }

    internal suspend fun releaseRepair(owner: Owner) = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        mutex.withLock { check(owner.activeRepairs > 0); owner.activeRepairs-- }
    }

    suspend fun <T> withRepairLease(owner: Owner, block: suspend () -> T): T {
        retainRepair(owner)
        // Retain ownership during network I/O without blocking other conversations.
        try {
            return block()
        } finally {
            releaseRepair(owner)
        }
    }

    /** Search resolves a viewport anchor without replacing the conversation's ingestion generation. */
    suspend fun locate(owner: Owner, target: TimelineMessageId): TimelinePageKey? = mutex.withLock {
        check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
        store.read(owner.selection.scope) { locate(target) }
    }

    suspend fun beginLive(owner: Owner): TimelineLiveFence = mutex.withLock {
        check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
        owner.session.beginLive(owner.selection).also { owner.liveFence = it }
    }

    suspend fun ingest(owner: Owner, fence: TimelineLiveFence, frame: TimelineStreamFrame): Boolean = mutex.withLock {
        if (owners[owner.selection.scope] !== owner || fence.selection !== owner.selection) return@withLock false
        owner.session.ingest(fence, frame).also { accepted ->
            if (accepted) releaseUnobservedSettlement(owner)
        }
    }

    suspend fun ingestExternal(owner: Owner, message: com.letta.mobile.data.model.LettaMessage): Boolean = mutex.withLock {
        if (owners[owner.selection.scope] !== owner) return@withLock false
        val fence = owner.liveFence ?: owner.session.beginLive(owner.selection).also { owner.liveFence = it }
        owner.session.ingest(fence, TimelineStreamFrame.Message(message))
    }

    suspend fun completeExternal(owner: Owner) = mutex.withLock {
        if (owners[owner.selection.scope] !== owner) return@withLock
        val fence = owner.liveFence ?: return@withLock
        owner.session.ingest(fence, TimelineStreamFrame.Done)
        releaseUnobservedSettlement(owner)
    }

    private suspend fun releaseUnobservedSettlement(owner: Owner) {
        if (owners[owner.selection.scope] !== owner) return
        if (owner.presentations.isNotEmpty()) return
        val fence = owner.liveFence ?: return
        if (owner.session.engine.releaseUnobservedSettlement(fence)) owner.liveFence = null
    }

    suspend fun acknowledgeSettlement(
        owner: Owner,
        fence: TimelineLiveFence,
        presented: Map<TimelineMessageId, Long>,
    ): Boolean = mutex.withLock {
        if (owners[owner.selection.scope] !== owner || fence.selection !== owner.selection) return@withLock false
        owner.session.acknowledgeSettlement(fence, presented).also { acknowledged ->
            if (acknowledged) owner.liveFence = null
        }
    }

    /** Not a screen-disposal callback. The runtime must release only after its users detach. */
    suspend fun retire(owner: Owner): Boolean = mutex.withLock {
        if (owners[owner.selection.scope] !== owner) return@withLock false
        if (owner.liveFence != null) return@withLock false
        if (owner.activeRepairs != 0) return@withLock false
        if (owner.presentations.isNotEmpty()) return@withLock false
        owner.session.close(owner.selection)
        owners.remove(owner.selection.scope)
        true
    }
}

/** Host-neutral session. Transport calls happen outside the engine storage mutex. */
class CanonicalTimelineSession(
    store: TimelineBoundedStore,
    private val transport: TimelineTransport,
    private val scope: TimelineScope,
    enabled: Boolean = false,
    budget: TimelinePageBudget = TimelinePageBudget(64, 2L * 1024 * 1024),
) {
    val engine = CanonicalTimelineEngine(
        store, TimelineExactCanonicalWriter(scope, minOf(budget.maxDecodedBodyBytes, Int.MAX_VALUE.toLong()).toInt()),
        budget, enabled,
    )
    val publication = engine.publication
    val live = engine.live
    private val pendingStore = CanonicalPendingLocalStore(store)
    private val pendingMutex = kotlinx.coroutines.sync.Mutex()
    private val mutablePending = kotlinx.coroutines.flow.MutableStateFlow<List<CanonicalPendingLocalStore.Record>>(emptyList())
    val pending: kotlinx.coroutines.flow.StateFlow<List<CanonicalPendingLocalStore.Record>> = mutablePending

    suspend fun open(target: TimelineMessageId? = null): TimelineEngineOpen {
        val opened = engine.open(scope, target)
        if (opened is TimelineEngineOpen.Opened) refreshPending()
        return opened
    }

    suspend fun appendPending(record: CanonicalPendingLocalStore.Record) = pendingMutex.withLock {
        pendingStore.save(scope, record)
        mutablePending.value = pendingStore.load(scope)
    }

    suspend fun markPending(otid: String, delivery: CanonicalPendingLocalStore.Delivery) = pendingMutex.withLock {
        pendingStore.mark(scope, otid, delivery)
        mutablePending.value = pendingStore.load(scope)
    }

    suspend fun discardFailedPending(otid: String): Boolean = pendingMutex.withLock {
        pendingStore.discardFailed(scope, otid).also { mutablePending.value = pendingStore.load(scope) }
    }

    private suspend fun refreshPending() = pendingMutex.withLock {
        mutablePending.value = pendingStore.load(scope)
    }

    suspend fun loadOlder(selection: TimelineEngineSelection): TimelineEnginePageOutcome {
        val request = engine.beginPage(selection)
        try {
            return when (val response = transport.listConversationMessagePage(request.remote)) {
                is TimelineRemotePageResult.Page -> engine.applyPage(request, response).also {
                    if (it == TimelineEnginePageOutcome.Applied) refreshPending()
                }
                is TimelineRemotePageResult.NoProgress -> engine.rejectNoProgress(request, response)
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                engine.cancelPage(request)
            }
        }
    }

    /** Fetch one bounded recent page without consuming the older-history continuation. */
    suspend fun reconcileRecent(selection: TimelineEngineSelection): TimelineEnginePageOutcome = reconcileRecentDetailed(selection).outcome

    suspend fun reconcileRecentDetailed(selection: TimelineEngineSelection): TimelineEngineReconcileResult {
        val request = engine.beginReconcile(selection)
        try {
            return when (val response = transport.listConversationMessagePage(request.remote)) {
                is TimelineRemotePageResult.Page -> engine.reconcilePageDetailed(request, response).also {
                    if (it.outcome == TimelineEnginePageOutcome.Applied) refreshPending()
                }
                is TimelineRemotePageResult.NoProgress -> TimelineEngineReconcileResult(engine.rejectReconcileNoProgress(request, response))
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                engine.cancelReconcile(request)
            }
        }
    }

    suspend fun beginLive(selection: TimelineEngineSelection): TimelineLiveFence = engine.beginLive(selection)

    suspend fun ingest(fence: TimelineLiveFence, frame: TimelineStreamFrame): Boolean =
        engine.ingest(fence, frame).also { accepted ->
            // Token updates never read pending storage; only a durable completion can confirm echoes.
            if (accepted && frame == TimelineStreamFrame.Done) refreshPending()
        }

    suspend fun acknowledgeSettlement(fence: TimelineLiveFence, presented: Map<TimelineMessageId, Long>): Boolean =
        engine.acknowledgeSettlement(fence, presented)

    suspend fun close(selection: TimelineEngineSelection) = engine.release(selection)
}
