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

    class Owner internal constructor(
        val session: CanonicalTimelineSession,
        val selection: TimelineEngineSelection,
    ) {
        internal var liveFence: TimelineLiveFence? = null
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
        owners[scope]?.let { return@withLock it }
        val session = CanonicalTimelineSession(store, transport, scope, enabled = true)
        val opened = session.open() as TimelineEngineOpen.Opened
        Owner(session, opened.selection).also { owners[scope] = it }
    }

    suspend fun current(scope: TimelineScope): Owner? = mutex.withLock { owners[scope] }

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

    suspend fun open(target: TimelineMessageId? = null): TimelineEngineOpen = engine.open(scope, target)

    suspend fun loadOlder(selection: TimelineEngineSelection): TimelineEnginePageOutcome {
        val request = engine.beginPage(selection)
        return when (val response = transport.listConversationMessagePage(request.remote)) {
            is TimelineRemotePageResult.Page -> engine.applyPage(request, response)
            is TimelineRemotePageResult.NoProgress -> engine.rejectNoProgress(request, response)
        }
    }

    suspend fun beginLive(selection: TimelineEngineSelection): TimelineLiveFence = engine.beginLive(selection)

    suspend fun ingest(fence: TimelineLiveFence, frame: TimelineStreamFrame): Boolean = engine.ingest(fence, frame)

    suspend fun acknowledgeSettlement(fence: TimelineLiveFence, presented: Map<TimelineMessageId, Long>): Boolean =
        engine.acknowledgeSettlement(fence, presented)

    suspend fun close(selection: TimelineEngineSelection) = engine.release(selection)
}
