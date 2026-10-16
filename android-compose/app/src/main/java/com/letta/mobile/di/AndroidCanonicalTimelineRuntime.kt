package com.letta.mobile.di

import com.letta.mobile.data.local.TimelineOwnedStorageFactory
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.local.TimelineOwnershipAuthority
import com.letta.mobile.data.session.CanonicalConversationWriters
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.plus

/**
 * letta-mobile-by4vm.15: the canonical ledger is the only Android timeline, for every backend.
 * The captured transport reads every conversation over the session graph's channel (admin_rpc;
 * by4vm.110.6 put the on-device runtime on the same channel).
 */
class AndroidCanonicalTimelineRuntimeFactory(
    private val authority: TimelineOwnershipAuthority,
    private val storage: TimelineOwnedStorageFactory,
    private val writers: CanonicalConversationWriters,
) {
    fun captured(
        graph: com.letta.mobile.data.session.SessionGraph,
        settings: com.letta.mobile.data.repository.api.ISettingsRepository,
        ownerScope: kotlinx.coroutines.CoroutineScope,
    ): AndroidCanonicalTimelineRuntime {
        val remote = com.letta.mobile.data.timeline.IrohAdminRpcTimelineTransport(graph.channelTransport, settings)
        val owned = com.letta.mobile.data.timeline.GenerationTimelineTransport(remote, ownerScope)
        return AndroidCanonicalTimelineRuntime(
            graph.backendDescriptor.backendId.value,
            owned,
            authority,
            storage,
            ownerScope,
            writers,
        )
    }
}

/** Android lease binding. Construct per captured transport generation, never from mutable settings. */
class AndroidCanonicalTimelineRuntime(
    private val backendId: String,
    private val transport: com.letta.mobile.data.timeline.GenerationTimelineTransport,
    private val authority: TimelineOwnershipAuthority,
    private val storage: TimelineOwnedStorageFactory,
    private val graphScope: kotlinx.coroutines.CoroutineScope,
    private val writers: CanonicalConversationWriters = CanonicalConversationWriters(),
) {
    private val maintenanceJob = kotlinx.coroutines.SupervisorJob(graphScope.coroutineContext[kotlinx.coroutines.Job])
    private val maintenanceScope = graphScope + maintenanceJob
    private val mutex = Mutex()
    private var retired = false
    private val bindings = mutableMapOf<TimelineScope, CanonicalTimelineCoordinator>()
    private val bindingCache = RuntimeBindingCache<TimelineScope, Binding>()

    init {
        graphScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { retire() }
            }
        }
    }

    /** Claims the scope for the canonical ledger; a scope it has never seen starts empty and pages from the server. */
    private suspend fun resume(scope: TimelineScope): CanonicalTimelineCoordinator = mutex.withLock {
        check(!retired && graphScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true && scope.backendId == backendId) { "Stale canonical backend generation" }
        bindings[scope]?.let { return@withLock it }
        // A scope never opened (or left mid-migration by an older build) is claimed without a copy.
        val lease = authority.claimCanonical(scope).also { authority.withLease(it) { } }
        CanonicalTimelineCoordinator(storage.canonical(lease), transport).also { bindings[scope] = it }
    }

    class Binding(
        val coordinator: CanonicalTimelineCoordinator,
        val owner: CanonicalTimelineCoordinator.Owner,
        val writer: com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter,
        internal val admission: com.letta.mobile.data.timeline.TimelineLegacyAdmission,
    ) {
        fun bindPresentation(host: com.letta.mobile.feature.chat.screen.ChatPagingHost) {
            host.bindCanonical(coordinator) { agent, conversation ->
                check(owner.selection.scope.agentId == agent && owner.selection.scope.conversationId == conversation)
                check(coordinator.current(owner.selection.scope) === owner) { "Retired presentation owner" }
                owner
            }
        }
    }

    /**
     * Ownership records are keyed by backend and conversation, and they pin the agent that first
     * touched the conversation. Callers bind from the runtime they happen to have captured, whose
     * agent is the selected one rather than the conversation's owner, so the requested scope can
     * name the wrong agent. Ask the record who owns it and bind under that agent: reading it needs
     * only the backend and conversation, so this costs nothing beyond the read that already
     * happens, and it keeps the ownership fence meaningful instead of tripping it.
     */
    private suspend fun ownedScope(requested: TimelineScope): TimelineScope {
        val owner = authority.ownerAgent(requested) ?: return requested
        if (owner == requested.agentId) return requested
        android.util.Log.i(
            "CanonicalTimeline",
            "ownership.rebound conversation=${requested.conversationId} " +
                "owner=$owner requested=${requested.agentId}",
        )
        return requested.copy(agentId = owner)
    }

    suspend fun bind(
        requested: TimelineScope,
        reportFailure: (Throwable) -> Unit,
    ): Binding = ownedScope(requested).let { scope -> bindingCache.get(scope) {
        val coordinator = resume(scope)
        val owner = coordinator.acquire(scope)
        val maintenance = com.letta.mobile.data.timeline.IndexedCanonicalTimelineMaintenance(
            coordinator, maintenanceScope,
            // The committed reconcile IS the cursor repair: Android keeps no retained watermark to
            // replace (the backend cursor table was never written), so there is nothing to CAS.
            repairCommittedCursor = { current, _, _ ->
                check(graphScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
                check(coordinator.current(scope) === current)
            },
            reportFailure = reportFailure,
        )
        val writer = com.letta.mobile.data.timeline.CanonicalExternalTransportWriter(coordinator, { agent, conversation ->
            check(graphScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
            check(scope.conversationId == conversation) { "Writer scope names another conversation" }
            check(agent == null || agent == scope.agentId || agent == requested.agentId) {
                "Writer scope names an agent that neither owns nor requested this conversation"
            }
            scope
        }, maintenance)
        val admission = com.letta.mobile.data.timeline.TimelineLegacyAdmission()
        val admitted = com.letta.mobile.data.timeline.AdmittedTimelineExternalWriter(writer, admission)
        writers.register(ConversationId(scope.conversationId), admitted)
        Binding(coordinator, owner, admitted, admission)
    } }

    /** Cancels RPCs and invalidates cached canonical owner handles. */
    suspend fun retire() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        bindingCache.close { cached ->
            mutex.withLock { retired = true }
            cached.forEach { binding ->
                writers.unregister(ConversationId(binding.owner.selection.scope.conversationId), binding.writer)
            }
            kotlinx.coroutines.coroutineScope {
                // UNDISPATCHED closes each gate before cancellation releases blocked RPCs.
                val drains = cached.map { binding ->
                    launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        binding.admission.close(binding.owner.selection.scope.conversationId)
                    }
                }
                maintenanceJob.cancel()
                transport.retire()
                maintenanceJob.join()
                drains.forEach { it.join() }
            }
            mutex.withLock {
                bindings.values.forEach { it.revoke() }
                bindings.clear()
            }
        }
    }
}

/** Serializes binding creation and retirement; failed creation is never cached. */
internal class RuntimeBindingCache<K, V> {
    private val mutex = Mutex()
    private val values = mutableMapOf<K, V>()
    private var closed = false

    suspend fun get(key: K, create: suspend () -> V): V = mutex.withLock {
        check(!closed) { "Canonical runtime binding cache retired" }
        values[key] ?: create().also { values[key] = it }
    }

    suspend fun close(cleanup: suspend (List<V>) -> Unit) = mutex.withLock {
        if (!closed) {
            closed = true
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                cleanup(values.values.toList())
                values.clear()
            }
        }
    }
}
