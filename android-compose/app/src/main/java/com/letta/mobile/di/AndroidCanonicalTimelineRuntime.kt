package com.letta.mobile.di

import com.letta.mobile.data.local.TimelineOwnedStorageFactory
import com.letta.mobile.data.local.TimelineOwnershipAuthority
import com.letta.mobile.data.timeline.CanonicalTimelineCoordinator
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.plus

class AndroidCanonicalTimelineRuntimeFactory(
    private val legacy: TimelineRepository,
    private val authority: TimelineOwnershipAuthority,
    private val storage: TimelineOwnedStorageFactory,
) {
    fun capturedIroh(
        graph: com.letta.mobile.data.session.SessionGraph,
        settings: com.letta.mobile.data.repository.api.ISettingsRepository,
        ownerScope: kotlinx.coroutines.CoroutineScope,
    ): AndroidCanonicalTimelineRuntime {
        require(graph.localRuntimeBackend == null) { "Local runtime is not a canonical remote timeline" }
        val transport = com.letta.mobile.data.timeline.IrohAdminRpcTimelineTransport(graph.channelTransport, settings)
        require(transport.shouldUseIroh()) { "Captured canonical runtime currently requires Iroh" }
        val owned = com.letta.mobile.data.timeline.GenerationTimelineTransport(transport, ownerScope)
        return AndroidCanonicalTimelineRuntime(
            graph.backendDescriptor.backendId.value,
            owned,
            { legacy.drainForCanonicalHandoff(it) },
            authority,
            storage,
            ownerScope,
            checkNotNull(graph.capturedConfig).id,
        )
    }
}

/** Android lease binding. Construct per captured transport generation, never from mutable settings. */
class AndroidCanonicalTimelineRuntime(
    private val backendId: String,
    private val transport: com.letta.mobile.data.timeline.GenerationTimelineTransport,
    private val drainLegacy: suspend (String) -> Unit,
    private val authority: TimelineOwnershipAuthority,
    private val storage: TimelineOwnedStorageFactory,
    private val graphScope: kotlinx.coroutines.CoroutineScope,
    private val legacyBackendId: String,
) {
    private val maintenanceJob = kotlinx.coroutines.SupervisorJob(graphScope.coroutineContext[kotlinx.coroutines.Job])
    private val maintenanceScope = graphScope + maintenanceJob
    private val mutex = Mutex()
    private var retired = false
    private val bindings = mutableMapOf<TimelineScope, CanonicalTimelineCoordinator>()
    private val bindingCache = RuntimeBindingCache<TimelineScope, BindResult>()
    var lastMeasurement = com.letta.mobile.data.local.CanonicalReadinessMeasurement()
        private set

    init {
        graphScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { retire() }
            }
        }
    }

    /** Only a durably switched scope can resume. Manifest-only history stays on the legacy route. */
    suspend fun resume(scope: TimelineScope): CanonicalTimelineCoordinator? = mutex.withLock {
        check(!retired && graphScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true && scope.backendId == backendId) { "Stale canonical backend generation" }
        bindings[scope]?.let { return@withLock it }
        val lease = readyLease(scope) ?: return@withLock null
        CanonicalTimelineCoordinator(storage.canonical(lease), transport).also { bindings[scope] = it }
    }

    private suspend fun readyLease(target: TimelineScope): TimelineOwnershipAuthority.Lease? {
        lastMeasurement = com.letta.mobile.data.local.CanonicalReadinessMeasurement()
        val decodeBefore = com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.envelopeDecodeCount
        try {
            try {
                return readyLeaseGuarded(target)
            } catch (failure: com.letta.mobile.data.local.TimelineMigrationAlreadyCompletedException) {
                // Another captured runtime may finish the durable cutover while this one drains.
                // Reopen only a verified canonical target; never hide an incomplete migration.
                if (authority.state(target).phase == TimelineOwnershipAuthority.Phase.Canonical) {
                    return storage.reopenCanonical(target)
                }
                throw failure
            }
        } finally {
            lastMeasurement = lastMeasurement.copy(
                envelopeDecodes = com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec.envelopeDecodeCount - decodeBefore,
            )
        }
    }

    private suspend fun readyLeaseGuarded(target: TimelineScope): TimelineOwnershipAuthority.Lease? {
        val source = target.copy(backendId = legacyBackendId)
        storage.registerLegacyPair(source, target)
        val state = authority.state(target)
        if (state.phase == TimelineOwnershipAuthority.Phase.Canonical) return storage.reopenCanonical(target)
        if (state.phase == TimelineOwnershipAuthority.Phase.Legacy &&
            authority.state(source).phase != TimelineOwnershipAuthority.Phase.Migrating) {
            val head = storage.classifyCopySource(source)
            if (head.kind == com.letta.mobile.data.local.LegacyLedgerCopyKind.ManifestOnly) {
                return null
            }
        }
        android.util.Log.i("CanonicalTimeline", "drain.start conversation=${target.conversationId}")
        drainLegacy(target.conversationId)
        android.util.Log.i("CanonicalTimeline", "drain.complete conversation=${target.conversationId}")
        val lease = when (state.phase) {
            TimelineOwnershipAuthority.Phase.Migrating, TimelineOwnershipAuthority.Phase.Prepared ->
                storage.resumeMappedMigration(source, target)
            TimelineOwnershipAuthority.Phase.Legacy -> {
                if (authority.state(source).phase == TimelineOwnershipAuthority.Phase.Migrating) {
                    storage.recoverMappedIntentAfterDrain(source, target)
                } else {
                    storage.beginMappedMigrationAfterDrain(
                        authority.acquire(source, TimelineOwnershipAuthority.Route.Legacy), target,
                    )
                }
            }
            TimelineOwnershipAuthority.Phase.Canonical -> error("Unexpected canonical transition")
        }
        if (authority.state(target).phase != TimelineOwnershipAuthority.Phase.Prepared) {
            var measurement = lastMeasurement
            boundedSteps("copy") {
                val result = storage.copyStep(lease)
                check(result is com.letta.mobile.data.local.LegacyLedgerCopyResult.Progress) { "Copy failed: $result" }
                check(result.rows <= 1 && result.bytes <= 65536) { "Copy step exceeded bound: $result" }
                measurement = measurement.copy(
                    copyRows = measurement.copyRows + result.rows,
                    copyBytes = Math.addExact(measurement.copyBytes, result.bytes.toLong()),
                    copySteps = measurement.copySteps + 1,
                )
                lastMeasurement = measurement
                result.complete
            }
            boundedSteps("convert") {
                val result = storage.convertStep(lease)
                check(result is com.letta.mobile.data.local.RoomCanonicalMigrationResult.Progress) { "Conversion failed: $result" }
                measurement = measurement.copy(
                    convertRows = result.convertedRows,
                    convertBytes = Math.addExact(measurement.convertBytes, result.bytes.toLong()),
                    convertSteps = measurement.convertSteps + 1,
                )
                lastMeasurement = measurement
                result.complete
            }
            var revision: Long? = null
            boundedSteps("validate") {
                val result = storage.validationStep(lease)
                check(result.metadataRows <= 128 && result.bodyBytes <= 65536) { "Validate step exceeded bound: $result" }
                measurement = measurement.copy(
                    validateRows = measurement.validateRows + result.metadataRows,
                    validateBytes = Math.addExact(measurement.validateBytes, result.bodyBytes.toLong()),
                    validateSteps = measurement.validateSteps + 1,
                )
                lastMeasurement = measurement
                if (result.complete) revision = checkNotNull(result.certifiedRevision)
                result.complete
            }
            storage.prepareAfterDrain(lease, checkNotNull(revision))
        }
        return storage.switchPreparedAfterDrain(lease)
    }

    sealed class BindResult {
        data class Canonical(val binding: Binding) : BindResult()
        data object LegacyDeferred : BindResult()
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

    /** Cursor callback must target the captured backend store, not the active settings store. */
    /**
     * Ownership records are keyed by backend and conversation, and they pin the agent that first
     * touched the conversation. Callers bind from the runtime they happen to have captured, whose
     * agent is the selected one rather than the conversation's owner, so the requested scope can
     * name the wrong agent. Ask the record who owns it and bind under that agent: reading it needs
     * only the backend and conversation, so this costs nothing beyond the read that already
     * happens, and it keeps the ownership fence meaningful instead of tripping it.
     */
    private suspend fun ownedScope(requested: TimelineScope): TimelineScope {
        val owner = authority.state(requested).scope.agentId ?: return requested
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
        repairCommittedCursor: suspend (CanonicalTimelineCoordinator.Owner, Long? /* expected */, Long? /* committed */) -> Unit,
        reportFailure: (Throwable) -> Unit,
    ): BindResult = ownedScope(requested).let { scope -> bindingCache.get(scope) {
        val coordinator = resume(scope)
        if (coordinator == null) BindResult.LegacyDeferred
        else {
        val owner = coordinator.acquire(scope)
        val maintenance = com.letta.mobile.data.timeline.IndexedCanonicalTimelineMaintenance(
            coordinator, maintenanceScope,
            repairCommittedCursor = { current, expected, seq ->
                check(graphScope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
                check(coordinator.current(scope) === current)
                repairCommittedCursor(current, expected, seq)
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
        BindResult.Canonical(Binding(coordinator, owner, com.letta.mobile.data.timeline.AdmittedTimelineExternalWriter(writer, admission), admission))
        }
    } }

    /** Cancels RPCs and invalidates cached canonical owner handles. */
    suspend fun retire() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        bindingCache.close { cached ->
            mutex.withLock { retired = true }
            val canonical = cached.mapNotNull { (it as? BindResult.Canonical)?.binding }
            kotlinx.coroutines.coroutineScope {
                // UNDISTPATCHED closes each gate before cancellation releases blocked RPCs.
                val drains = canonical.map { binding ->
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

/**
 * Runs bounded copy/convert/validate slices until the stage completes.
 * [slice] is a cooperative yield interval, not a readiness failure. A 28k-row
 * copy is ~110 slices at one metadata row per step; throwing at 256 made large
 * histories unopenable. [maxSteps] is only a stuck detector. Keep [maxSteps]
 * as the second parameter so `boundedSteps("copy", 3)` remains a stuck cap.
 */
internal suspend fun boundedSteps(
    stage: String,
    maxSteps: Int = 4_194_304,
    slice: Int = 256,
    step: suspend () -> Boolean,
) {
    require(slice > 0 && maxSteps > 0)
    var steps = 0
    while (true) {
        if (step()) return
        steps++
        check(steps < maxSteps) { "Canonical $stage budget exhausted after $steps bounded steps" }
        if (steps % slice == 0) kotlinx.coroutines.yield()
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
