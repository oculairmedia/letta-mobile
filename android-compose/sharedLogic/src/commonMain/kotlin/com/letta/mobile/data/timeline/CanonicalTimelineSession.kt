package com.letta.mobile.data.timeline

import com.letta.mobile.util.attr
import com.letta.mobile.data.timeline.api.PendingDispatchOwners
import com.letta.mobile.data.timeline.api.PendingDispatchTimelineStore
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.MessageId
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.sync.withLock

/** Conversation ownership is independent of screen selection; hosts explicitly retire idle owners. */
class CanonicalTimelineCoordinator(
    private val store: TimelineBoundedStore,
    private val transport: TimelineTransport,
    internal val dispatchOwners: PendingDispatchOwners = PendingDispatchOwners(),
) {
    internal val pendingStorage: TimelineBoundedStore get() = store
    private val sessionStore = PendingDispatchTimelineStore(store, dispatchOwners)
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val owners = mutableMapOf<TimelineScope, Owner>()
    private var revoked = false
    private val retryLock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val retrySenders = mutableMapOf<AgentId, suspend (ConversationId, CanonicalPendingLocalStore.Record) -> Unit>()

    internal fun bindPendingRetry(agent: AgentId, sender: suspend (ConversationId, CanonicalPendingLocalStore.Record) -> Unit) {
        kotlinx.atomicfu.locks.synchronized(retryLock) { retrySenders[agent] = sender }
    }

    internal suspend fun retryPending(owner: Owner, identity: MessageId): Boolean {
        val scope = owner.selection.scope
        return dispatchOwners.protecting(Triple(scope.backendId, scope.conversationId, identity.value)) {
            echoArrivedBeforeRetry(owner, identity) || dispatchRetry(owner, identity)
        }
    }

    /**
     * A failed prompt may have reached the host before contact was lost, and the App Server does
     * not deduplicate `client_message_id`, so a blind resend can post it twice. Recent history is
     * read first: when it holds the echo, the prompt is confirmed and there is nothing to resend.
     */
    private suspend fun echoArrivedBeforeRetry(owner: Owner, identity: MessageId): Boolean {
        val failed = owner.session.pending.value.any {
            it.otid == identity.value && it.delivery == CanonicalPendingLocalStore.Delivery.Failed
        }
        if (!failed) return false
        reconcileRecent(owner)
        return owner.session.pending.value.none { it.otid == identity.value }
    }

    private suspend fun dispatchRetry(owner: Owner, identity: MessageId): Boolean {
        val (record, sender) = mutex.withLock {
            check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
            val record = owner.session.pending.value.singleOrNull {
                it.otid == identity.value && it.delivery == CanonicalPendingLocalStore.Delivery.Failed
            } ?: return false
            val sender = owner.selection.scope.agentId?.let(::AgentId)?.let { agent ->
                kotlinx.atomicfu.locks.synchronized(retryLock) { retrySenders[agent] }
            } ?: return false
            val retry = owner.session.retryPending(record.otid) ?: return false
            retry to sender
        }
        val result = runCatching {
            // Never hold the canonical mutex while dispatch calls back into appendPending.
            sender(ConversationId(owner.selection.scope.conversationId), record)
        }
        result.exceptionOrNull()?.let { failure ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                owner.session.markPending(record.otid, CanonicalPendingLocalStore.Delivery.Failed, record.attempt)
            }
            if (failure is kotlinx.coroutines.CancellationException) throw failure
        }
        return result.isSuccess
    }

    internal suspend fun dismissPending(owner: Owner, identity: MessageId): Boolean = mutex.withLock {
        check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
        owner.session.discardFailed(identity)
    }

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
        /**
         * The turn that settled last, kept until the next turn opens. Its overlay may already be
         * released (no viewport) or acknowledged, but a transport can still deliver its tail.
         */
        internal var settledTail: TimelineLivePublication? = null

        /** The turn a late frame could belong to: the resident settled overlay, else the last one. */
        internal fun settledTurn(): TimelineLivePublication? {
            val live = session.live.value
            if (live?.settlementRevision != null) return live
            return settledTail.takeIf { liveFence == null }
        }

        /** The fence of a settled overlay still resident for this owner, able to take a tail. */
        internal fun residentSettledFence(): TimelineLiveFence? =
            session.live.value?.takeIf { it.settlementRevision != null && it.fence === liveFence }?.fence
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
        val session = CanonicalTimelineSession(sessionStore, transport, scope, enabled = true)
        val opened = session.open() as TimelineEngineOpen.Opened
        Owner(session, opened.selection).also { owners[scope] = it }
    }

    suspend fun current(scope: TimelineScope): Owner? = mutex.withLock { owners[scope] }

    suspend fun appendPending(owner: Owner, record: CanonicalPendingLocalStore.Record) = mutex.withLock {
        check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
        owner.session.appendPending(record)
    }

    /** Apply an outcome only to the expected retry generation when one is supplied. */
    suspend fun markPending(
        owner: Owner, otid: String, delivery: CanonicalPendingLocalStore.Delivery, expectedAttempt: Long? = null,
    ) = mutex.withLock {
        check(owners[owner.selection.scope] === owner) { "Stale canonical owner" }
        owner.session.markPending(otid, delivery, expectedAttempt)
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
        openLive(owner)
    }

    private suspend fun openLive(owner: Owner): TimelineLiveFence =
        owner.session.beginLive(owner.selection).also {
            owner.liveFence = it
            owner.settledTail = null
        }

    suspend fun ingest(owner: Owner, fence: TimelineLiveFence, frame: TimelineStreamFrame): Boolean =
        mutex.withLock { ingestLocked(owner, fence, frame) }

    private suspend fun ingestLocked(owner: Owner, fence: TimelineLiveFence, frame: TimelineStreamFrame): Boolean {
        if (owners[owner.selection.scope] !== owner || fence.selection !== owner.selection) return false
        val accepted = owner.session.ingest(fence, frame)
        if (!accepted) return false
        if (frame == TimelineStreamFrame.Done) rememberSettlement(owner)
        releaseUnobservedSettlement(owner)
        return true
    }

    /**
     * [retiredTail] is the send coordinator's word that [message] belongs to a turn it already
     * finished. Such a frame never opens a turn, whatever it names.
     */
    suspend fun ingestExternal(
        owner: Owner,
        message: LettaMessage,
        retiredTail: Boolean = false,
    ): Boolean = mutex.withLock { ingestExternalLocked(owner, message, retiredTail) }

    private suspend fun ingestExternalLocked(
        owner: Owner,
        message: LettaMessage,
        retiredTail: Boolean,
    ): Boolean {
        if (owners[owner.selection.scope] !== owner) return false
        if (absorbsSettledTail(owner, message, retiredTail)) return true
        val frame = TimelineStreamFrame.Message(message)
        val fence = owner.liveFence ?: openLive(owner)
        if (owner.session.ingest(fence, frame)) return true
        // A frame arriving after this conversation's last turn settled belongs to the next turn, not
        // to an error. The settled overlay refuses it until the screen acknowledges settlement, and
        // that acknowledgement waits on a reconcile, so an agent replying twice in a row would
        // otherwise be rejected. Open the fence the absent turnStarted would have opened.
        if (owner.session.live.value?.settlementRevision == null) return false
        return owner.session.ingest(openLive(owner), frame)
    }

    /**
     * A frame of the turn that just settled is its tail, not the next turn. Transports deliver a
     * reply's last deltas after its terminal (Iroh emits them behind turn_finished, device capture
     * 2026-09-24). Opening a fence for one left a live turn nothing would ever settle: every
     * turn-end repair was refused as NoProgress, the user's echo never confirmed, and its pending
     * row held the composer on Thinking. The settled overlay is frozen and reconcile carries the
     * durable row, so the tail is absorbed here.
     */
    private suspend fun absorbsSettledTail(
        owner: Owner,
        message: LettaMessage,
        retiredTail: Boolean,
    ): Boolean {
        if (!retiredTail && owner.settledTurn()?.claimsLateTail(message) != true) return false
        // While the settled overlay is still on screen the tail completes it, so the reply renders
        // whole and adopts its stored row by content. Otherwise reconcile carries the durable row.
        val folded = owner.residentSettledFence()?.let { owner.session.engine.ingestSettledTail(it, message) } ?: false
        com.letta.mobile.util.Telemetry.event(
            "CanonicalTimeline", "external.settledTailAbsorbed",
            "messageType" attr message.messageType, "runId" attr (message.runId ?: ""),
            "folded" attr folded, "signalled" attr retiredTail,
        )
        return true
    }

    private fun rememberSettlement(owner: Owner) {
        owner.session.live.value?.takeIf { it.settlementRevision != null }?.let { owner.settledTail = it }
    }

    suspend fun completeExternal(owner: Owner) = mutex.withLock {
        if (owners[owner.selection.scope] !== owner) return@withLock
        val fence = owner.liveFence ?: return@withLock
        owner.session.ingest(fence, TimelineStreamFrame.Done)
        rememberSettlement(owner)
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
    val settling = engine.settling
    private var recoveryCandidates = emptySet<MessageId>()
    private var recoveryHistoryStart: TimelineInstant? = null
    private val dispatchOwners = (store as? PendingDispatchTimelineStore)?.dispatchOwners ?: PendingDispatchOwners()
    // Paging protection ends at the lost-echo horizon, even if the user never loads older pages.
    private val pendingStore = CanonicalPendingLocalStore(store, dispatchOwners)
    private val pendingMutex = kotlinx.coroutines.sync.Mutex()
    private val mutablePending = kotlinx.coroutines.flow.MutableStateFlow<List<CanonicalPendingLocalStore.Record>>(emptyList())
    val pending: kotlinx.coroutines.flow.StateFlow<List<CanonicalPendingLocalStore.Record>> = mutablePending

    suspend fun open(target: TimelineMessageId? = null): TimelineEngineOpen {
        val opened = engine.open(scope, target)
        if (opened is TimelineEngineOpen.Opened) {
            mutablePending.value = pendingStore.load(scope)
            // These sends predate this session. Successful history reads decide covered sends;
            // older candidates can await more pages only until the lost-echo horizon.
            recoveryCandidates = pending.value.mapTo(mutableSetOf()) { MessageId(it.otid) }
            recoveryHistoryStart = null
        }
        return opened
    }

    suspend fun appendPending(record: CanonicalPendingLocalStore.Record) = pendingMutex.withLock {
        pendingStore.save(scope, record)
        mutablePending.value = pendingStore.load(scope)
    }

    internal suspend fun markPending(
        otid: String, delivery: CanonicalPendingLocalStore.Delivery, expectedAttempt: Long? = null,
    ) = pendingMutex.withLock {
        pendingStore.mark(scope, otid, delivery, expectedAttempt)
        if (delivery == CanonicalPendingLocalStore.Delivery.Sending) recoveryCandidates -= MessageId(otid)
        mutablePending.value = pendingStore.load(scope)
    }

    internal suspend fun retryPending(otid: String): CanonicalPendingLocalStore.Record? = pendingMutex.withLock {
        val retry = pendingStore.retry(scope, otid)
        recoveryCandidates -= MessageId(otid)
        mutablePending.value = pendingStore.load(scope)
        retry
    }

    internal suspend fun discardFailed(identity: MessageId): Boolean = pendingMutex.withLock {
        val discarded = pendingStore.discardFailed(scope, identity.value)
        if (discarded) {
            recoveryCandidates -= identity
            mutablePending.value = pendingStore.load(scope)
        }
        discarded
    }

    private suspend fun refreshPending(historyPage: TimelineRemotePageResult.Page? = null) = pendingMutex.withLock {
        val loaded = pendingStore.load(scope)
        recoveryHistoryStart = (listOfNotNull(recoveryHistoryStart) + historyPage?.records.orEmpty().mapNotNull {
            it.message.date?.let(::parseTimelineInstantOrNull)
        }).minWithOrNull(Comparator(::compareTimelineInstants))
        // The canonical writer already removed matching echoes from pending storage. Absence is
        // decisive once the loaded window spans a candidate's send time, even with more pages.
        val abandoned = loaded.filter {
            MessageId(it.otid) in recoveryCandidates && it.delivery != CanonicalPendingLocalStore.Delivery.Failed &&
                !dispatchOwners.isActive(scope.backendId, scope.conversationId, it.otid) &&
                (historyPage?.hasMore == false || historyCovers(it.sentAt))
        }
        abandoned.forEach {
            pendingStore.mark(scope, it.otid, CanonicalPendingLocalStore.Delivery.Failed, it.attempt)
        }
        recoveryCandidates -= abandoned.map { MessageId(it.otid) }.toSet()
        recoveryCandidates = recoveryCandidates.intersect(loaded.map { MessageId(it.otid) }.toSet())
        val retired = pendingStore.retireLostEchoes(scope, loaded)
        mutablePending.value = if (abandoned.isNotEmpty() || retired > 0) pendingStore.load(scope) else loaded
    }

    private fun historyCovers(sentAt: String): Boolean {
        val oldest = recoveryHistoryStart ?: return false
        val sent = parseTimelineInstantOrNull(sentAt) ?: return false
        return compareTimelineInstants(sent, oldest) >= 0
    }

    suspend fun loadOlder(selection: TimelineEngineSelection): TimelineEnginePageOutcome {
        val request = engine.beginPage(selection)
        try {
            return when (val response = transport.listConversationMessagePage(request.remote)) {
                is TimelineRemotePageResult.Page -> engine.applyPage(request, response).also {
                    if (it == TimelineEnginePageOutcome.Applied) refreshPending(historyPage = response)
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
                    if (it.outcome == TimelineEnginePageOutcome.Applied) refreshPending(historyPage = response)
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
