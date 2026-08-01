package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Long-lived coroutine scope SubagentRepository uses for its push observer and
 * reconnect watcher. Defaults to [Dispatchers.Default] + a fresh
 * [SupervisorJob]. Exposed as a factory so tests can substitute a
 * `kotlinx.coroutines.test.TestScope`.
 *
 * (Common code can't reference `Dispatchers.IO`; the scope only collects flows
 * and launches transport round-trips, so [Dispatchers.Default] is appropriate —
 * the actual socket I/O happens inside the platform [IChannelTransport].)
 */
internal fun defaultSubagentScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * letta-mobile-73o2h.3: single source of truth for the active-subagent
 * registry, driven by the shim's mobile WS subagent protocol
 * (`IChannelTransport.sendSubagentList`).
 *
 * Platform-neutral: it depends only on the common [IChannelTransport] surface,
 * so the same registry logic backs both the mobile WS transport and (once it
 * exists) a desktop WS transport (letta-mobile-0yf7o).
 *
 * The registry is per-socket (NOT per-agent), so unlike CronRepository there is
 * exactly one shared [MutableStateFlow] snapshot rather than a map keyed by
 * agent id.
 *
 * Lifecycle:
 *  - The first subscriber triggers a `subagent_list` WS round-trip; subsequent
 *    subscribers share the same [kotlinx.coroutines.flow.StateFlow] so no
 *    duplicate fetches fire.
 *  - A `subagents_updated` push folds its `subagents_active` snapshot into the
 *    cache without dropping previously running entries that are merely omitted;
 *    explicit terminal states remove entries.
 *  - On WS reconnect (`Disconnected → Connected`) the snapshot is refreshed so
 *    the UI doesn't keep showing a stale list after a dropped socket.
 *
 * letta-mobile-sqdqe: INCREMENTAL push snapshots can be transiently
 * incomplete, so replacement is conservative: running entries survive omission
 * until the shim sends an explicit terminal state or absence exceeds a bound.
 * AUTHORITATIVE (refresh) snapshots are ground truth — a running entry omitted
 * from a full subagent_list response is EVICTED immediately.
 */
open class SubagentRepository(
    private val transport: IChannelTransport,
    private val scope: CoroutineScope = defaultSubagentScope(),
    // Mobile shows only active subagents (all=false). The desktop Background
    // tasks panel also has a "Finished" section, so it requests all=true to
    // include recently-terminal entries in the initial snapshot.
    private val includeAll: Boolean = false,
    private val clock: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : ISubagentRepository {
    private val state = MutableStateFlow<List<SubagentEntry>>(emptyList())
    private val inFlightRefresh = atomic<CompletableDeferred<Result<List<SubagentEntry>>>?>(null)
    // Whether the initial subagent_list has been dispatched. Repeated
    // subscribe/unsubscribe must not duplicate the initial fetch.
    private val initialized = atomic(false)
    // Track when a running entry was first noticed absent from an INCREMENTAL
    // push so absence-bound eviction can fire later.
    //
    // Held as an immutable map behind an atomic reference: `mergeSnapshot` is
    // reached concurrently from `pushJob` (observePushEvents) and `refresh()`,
    // both on Dispatchers.Default, so a bare mutable map could be structurally
    // mutated from two threads at once. Each merge reads one consistent
    // snapshot, mutates a private local copy, and publishes it once.
    private val runningAbsentSince = atomic<Map<String, Long>>(emptyMap())

    private val pushJob = scope.launch { observePushEvents() }
    private val reconnectJob = scope.launch { observeReconnects() }

    /**
     * Stops this registry's collectors. Required when the owner replaces the
     * repository (transport/backend switch): without it the two collectors
     * above run for the life of [scope], which for the default scope means
     * forever. Idempotent, and deliberately does NOT cancel a caller-supplied
     * [scope] — that belongs to the caller.
     */
    fun close() {
        pushJob.cancel()
        reconnectJob.cancel()
    }

    /**
     * Hot stream of the active-subagent snapshot. The flow is shared across all
     * subscribers; only the first subscription triggers the initial
     * `subagent_list` round-trip.
     */
    override fun activeSubagentsFlow(scope: SubagentParentScope): Flow<List<SubagentEntry>> {
        if (initialized.compareAndSet(expect = false, update = true)) {
            this.scope.launch { refresh() }
        }
        return state.asStateFlow().map { entries -> entries.inParentScope(scope) }
    }

    override fun currentActiveSubagents(scope: SubagentParentScope): List<SubagentEntry> =
        state.value.inParentScope(scope)

    /**
     * Force a fresh `subagent_list` round-trip. Parallel callers (e.g. the
     * reconnect watcher racing the initial fetch) share the same in-flight
     * deferred so the shim never sees duplicate `subagent_list` frames.
     */
    override suspend fun refresh(): Result<List<SubagentEntry>> {
        while (true) {
            val current = inFlightRefresh.value
            if (current != null && !current.isCompleted) {
                return current.await()
            }
            val deferred = CompletableDeferred<Result<List<SubagentEntry>>>()
            if (inFlightRefresh.compareAndSet(current, deferred)) {
                val result = runCatching {
            val response = transport.sendSubagentList(all = includeAll)
            if (!response.success) {
                throw IllegalStateException(response.error ?: "subagent_list failed")
            }
            val subagents = mergeSnapshot(response.subagents, kind = SnapshotKind.AUTHORITATIVE)
            state.value = subagents
            subagents
        }
                deferred.complete(result)
                inFlightRefresh.compareAndSet(deferred, null)
                return result
            }
        }
    }

    /**
     * Fetch one subagent's latest TodoWrite snapshot (§13.3). Resolves on the
     * matching `subagent_todos_response`; degrades to an empty list when the
     * shim reports the todos could not be resolved.
     */
    override suspend fun todos(toolCallId: String): Result<List<SubagentTodo>> = runCatching {
        val response = transport.sendSubagentTodos(toolCallId)
        if (!response.success) {
            throw IllegalStateException(response.error ?: "subagent_todos failed")
        }
        response.todos
    }

    enum class SnapshotKind { AUTHORITATIVE, INCREMENTAL }

    private fun mergeSnapshot(
        incoming: List<SubagentEntry>,
        terminal: SubagentEntry? = null,
        kind: SnapshotKind,
    ): List<SubagentEntry> {
        val now = clock()
        val stampedTerminal = terminal
            ?.takeIf { it.status in TERMINAL_STATUSES }
            ?.let { entry -> entry.copy(terminalAtEpochMs = entry.terminalAtEpochMs ?: now) }
        val completeIncoming = if (stampedTerminal == null) incoming else incoming + stampedTerminal
        val incomingByKey = completeIncoming.associateBy { it.cacheKey() }
        val terminalKey = stampedTerminal?.cacheKey()

        // One consistent read of the absence clock for the whole merge; all
        // mutations below go to this private local copy and are published once
        // at the end, so no other thread can observe a half-updated map.
        val absence = runningAbsentSince.value.toMutableMap()

        // Clear absence tracking for entries that ARE present in this snapshot.
        incomingByKey.keys.forEach { key -> absence.remove(key) }

        val absentRunning = absentRunningEntries(incomingByKey, terminalKey)
        val retained = when (kind) {
            SnapshotKind.AUTHORITATIVE -> {
                emitDeltaRunningCount(incoming)
                evictAbsentRunning(absentRunning, absence, kind)
                emptyList()
            }
            SnapshotKind.INCREMENTAL -> retainWithinLinger(absentRunning, absence, now, kind)
        }

        // Publish the absence clock once, after every branch above has settled.
        runningAbsentSince.value = absence.toMap()

        val previousTerminals = state.value.filter { previous ->
            previous.status in TERMINAL_STATUSES &&
                previous.cacheKey() !in incomingByKey &&
                previous.terminalAtEpochMs?.let { now - it < TERMINAL_LINGER_MS } == true
        }
        return (completeIncoming + retained + previousTerminals).distinctBy { it.cacheKey() }
    }

    /** Locally-RUNNING entries that this snapshot did not mention. */
    private fun absentRunningEntries(
        incomingByKey: Map<String, SubagentEntry>,
        terminalKey: String?,
    ): List<SubagentEntry> = state.value.filter { previous ->
        val key = previous.cacheKey()
        previous.status == SubagentStatus.RUNNING &&
            key !in incomingByKey &&
            key != terminalKey
    }

    /** Delta signal: AUTHORITATIVE snapshot count disagrees with local. */
    private fun emitDeltaRunningCount(incoming: List<SubagentEntry>) {
        val localRunning = state.value.count { it.status == SubagentStatus.RUNNING }
        val incomingRunning = incoming.count { it.status == SubagentStatus.RUNNING }
        if (localRunning == incomingRunning) return
        Telemetry.event(
            "SubagentRepo",
            "merge.deltaRunningCount",
            "localRunning" to localRunning,
            "incomingRunning" to incomingRunning,
            "delta" to (localRunning - incomingRunning),
        )
    }

    /**
     * Ground truth: running entries omitted from a full subagent_list
     * response are EVICTED immediately.
     */
    private fun evictAbsentRunning(
        absentRunning: List<SubagentEntry>,
        absence: MutableMap<String, Long>,
        kind: SnapshotKind,
    ) {
        absentRunning.forEach { entry ->
            val key = entry.cacheKey()
            absence.remove(key)
            Telemetry.event(
                "SubagentRepo",
                "merge.evictRunning",
                "cacheKey" to key,
                "status" to entry.status,
                "kind" to kind.name,
                "reason" to "absent-from-authoritative-snapshot",
            )
        }
    }

    /**
     * Conservative: retain running entries omitted from a push, bounded by
     * absence linger to prevent unbounded retention.
     */
    private fun retainWithinLinger(
        absentRunning: List<SubagentEntry>,
        absence: MutableMap<String, Long>,
        now: Long,
        kind: SnapshotKind,
    ): List<SubagentEntry> = absentRunning.filter { previous ->
        val key = previous.cacheKey()
        val absentSince = absence[key]
        if (absentSince == null) {
            absence[key] = now
            Telemetry.event(
                "SubagentRepo",
                "merge.retainRunning",
                "cacheKey" to key,
                "status" to previous.status,
                "ageMs" to 0L,
                "kind" to kind.name,
                "reason" to "absent-from-push-retained",
            )
            return@filter true
        }
        val ageMs = now - absentSince
        if (ageMs < RUNNING_ABSENCE_LINGER_MS) {
            return@filter true
        }
        absence.remove(key)
        Telemetry.event(
            "SubagentRepo",
            "merge.evictRunning",
            "cacheKey" to key,
            "status" to previous.status,
            "ageMs" to ageMs,
            "kind" to kind.name,
            "reason" to "absent-from-push-exceeded-linger",
        )
        false
    }

    private fun SubagentEntry.cacheKey(): String = listOf(
        parentAgentId.orEmpty(),
        parentConversationId.orEmpty(),
        toolCallId.takeIf { it.isNotBlank() }
            ?: taskId?.takeIf { it.isNotBlank() }
            ?: hashCode().toString(),
    ).joinToString("|")

    private fun List<SubagentEntry>.inParentScope(scope: SubagentParentScope): List<SubagentEntry> =
        filter { entry ->
            entry.parentAgentId == scope.parentAgentId &&
                entry.parentConversationId == scope.parentConversationId
        }

    private suspend fun observePushEvents() {
        transport.events.collect { frame ->
            if (frame !is ServerFrame.SubagentsUpdated) return@collect
            // Mark initialized so a later first-subscriber doesn't kick off a
            // redundant subagent_list (the cache is already warm).
            initialized.value = true
            state.value = mergeSnapshot(frame.subagentsActive, terminal = frame.subagent, kind = SnapshotKind.INCREMENTAL)
        }
    }

    private suspend fun observeReconnects() {
        var wasConnected: Boolean? = null
        transport.state.collect { connectionState ->
            val nowConnected = connectionState is ChannelTransportState.Connected
            if (wasConnected == false && nowConnected && initialized.value) {
                // Best-effort refresh; a failure here is non-fatal (the next
                // push or manual refresh recovers the snapshot).
                runCatching { refresh() }
            }
            wasConnected = nowConnected
        }
    }

    companion object {
        private const val TERMINAL_LINGER_MS = 8_000L
        private const val RUNNING_ABSENCE_LINGER_MS = 60_000L
        private val TERMINAL_STATUSES = setOf(
            SubagentStatus.COMPLETED,
            SubagentStatus.FAILED,
            SubagentStatus.CANCELLED,
        )
    }
}
