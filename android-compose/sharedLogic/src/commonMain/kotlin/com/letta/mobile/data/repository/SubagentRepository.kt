package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
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
 * letta-mobile-sqdqe: push/refresh snapshots can be transiently incomplete, so
 * replacement is conservative: running entries survive omission until the shim
 * sends an explicit terminal state.
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

    init {
        scope.launch { observePushEvents() }
        scope.launch { observeReconnects() }
    }

    /**
     * Hot stream of the active-subagent snapshot. The flow is shared across all
     * subscribers; only the first subscription triggers the initial
     * `subagent_list` round-trip.
     */
    override fun activeSubagentsFlow(scope: SubagentParentScope): Flow<List<SubagentEntry>> {
        if (initialized.compareAndSet(false, true)) {
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
            val subagents = mergeSnapshot(response.subagents)
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

    private fun mergeSnapshot(
        incoming: List<SubagentEntry>,
        terminal: SubagentEntry? = null,
    ): List<SubagentEntry> {
        val stampedTerminal = terminal
            ?.takeIf { it.status in TERMINAL_STATUSES }
            ?.let { entry -> entry.copy(terminalAtEpochMs = entry.terminalAtEpochMs ?: clock()) }
        val completeIncoming = if (stampedTerminal == null) incoming else incoming + stampedTerminal
        val incomingByKey = completeIncoming.associateBy { it.cacheKey() }
        val terminalKey = stampedTerminal?.cacheKey()
        val retained = state.value.filter { previous ->
            val key = previous.cacheKey()
            previous.status == SubagentStatus.RUNNING &&
                key !in incomingByKey &&
                key != terminalKey
        }
        val previousTerminals = state.value.filter { previous ->
            previous.status in TERMINAL_STATUSES &&
                previous.cacheKey() !in incomingByKey &&
                previous.terminalAtEpochMs?.let { clock() - it < TERMINAL_LINGER_MS } == true
        }
        return (completeIncoming + retained + previousTerminals).distinctBy { it.cacheKey() }
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
            state.value = mergeSnapshot(frame.subagentsActive, terminal = frame.subagent)
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
        private val TERMINAL_STATUSES = setOf(
            SubagentStatus.COMPLETED,
            SubagentStatus.FAILED,
            SubagentStatus.CANCELLED,
        )
    }
}
