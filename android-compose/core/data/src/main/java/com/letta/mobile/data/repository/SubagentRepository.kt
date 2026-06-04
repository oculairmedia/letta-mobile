package com.letta.mobile.data.repository

import android.util.Log
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentTodo
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.ServerFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-lived coroutine scope SubagentRepository uses for its push observer
 * and reconnect watcher. Defaults to [Dispatchers.IO] + a fresh
 * [SupervisorJob] — same pattern [CronRepository] uses. Exposed as a
 * factory so tests can substitute a [kotlinx.coroutines.test.TestScope].
 */
internal fun defaultSubagentScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * letta-mobile-73o2h.3: single source of truth for the active-subagent
 * registry, driven by the shim's mobile WS subagent protocol (the wire
 * layer landed in letta-mobile-73o2h.1 / [ChannelTransport.sendSubagentList]).
 *
 * The registry is per-socket (NOT per-agent), so unlike [CronRepository]
 * there is exactly one shared [MutableStateFlow] snapshot rather than a map
 * keyed by agent id.
 *
 * Lifecycle:
 *  - The first subscriber triggers a `subagent_list` WS round-trip;
 *    subsequent subscribers share the same [kotlinx.coroutines.flow.StateFlow]
 *    so no duplicate fetches fire.
 *  - A `subagents_updated` push folds its fresh `subagents_active` snapshot
 *    straight into the cache by replacement — no re-fetch needed (the push
 *    already carries the canonical active set, mirroring how the bar reduces).
 *  - On WS reconnect (`Disconnected → Connected`) the snapshot is refreshed
 *    so the UI doesn't keep showing a stale list after a dropped socket.
 *
 * Snapshot-by-replacement semantics are preserved end-to-end: every emission
 * is the full active set, never a delta — do not regress the rmzmo perf work.
 */
@Singleton
open class SubagentRepository(
    private val transport: IChannelTransport,
    private val scope: CoroutineScope,
) : ISubagentRepository {
    /**
     * Hilt-friendly constructor — uses a fresh [defaultSubagentScope] tied
     * to the singleton's lifetime. Tests inject their own scope via the
     * primary constructor.
     */
    @Inject
    constructor(transport: IChannelTransport) : this(transport, defaultSubagentScope())

    private val state = MutableStateFlow<List<SubagentEntry>>(emptyList())
    private val inFlightRefresh = AtomicReference<CompletableDeferred<Result<List<SubagentEntry>>>?>(null)
    // Whether the initial subagent_list has been dispatched. Repeated
    // subscribe/unsubscribe must not duplicate the initial fetch.
    private val initialized = AtomicBoolean(false)

    init {
        scope.launch { observePushEvents() }
        scope.launch { observeReconnects() }
    }

    /**
     * Hot stream of the active-subagent snapshot. The flow is shared across
     * all subscribers; only the first subscription triggers the initial
     * `subagent_list` round-trip.
     */
    override fun activeSubagentsFlow(): Flow<List<SubagentEntry>> {
        if (initialized.compareAndSet(false, true)) {
            scope.launch { refresh() }
        }
        return state.asStateFlow()
    }

    /**
     * Force a fresh `subagent_list` round-trip. Parallel callers (e.g. the
     * reconnect watcher racing the initial fetch) share the same in-flight
     * deferred so the shim never sees duplicate `subagent_list` frames.
     */
    override suspend fun refresh(): Result<List<SubagentEntry>> {
        inFlightRefresh.get()?.takeIf { !it.isCompleted }?.let { return it.await() }
        val deferred = CompletableDeferred<Result<List<SubagentEntry>>>()
        val previous = inFlightRefresh.getAndSet(deferred)
        previous?.takeIf { !it.isCompleted }?.cancel()
        val result = runCatching {
            val response = transport.sendSubagentList(all = false)
            if (!response.success) {
                throw IllegalStateException(response.error ?: "subagent_list failed")
            }
            val subagents = response.subagents
            state.value = subagents
            subagents
        }
        deferred.complete(result)
        inFlightRefresh.compareAndSet(deferred, null)
        return result
    }

    /**
     * Fetch one subagent's latest TodoWrite snapshot (§13.3). Resolves on
     * the matching `subagent_todos_response`; degrades to an empty list when
     * the shim reports the todos could not be resolved.
     */
    override suspend fun todos(toolCallId: String): Result<List<SubagentTodo>> = runCatching {
        val response = transport.sendSubagentTodos(toolCallId)
        if (!response.success) {
            throw IllegalStateException(response.error ?: "subagent_todos failed")
        }
        response.todos
    }

    private suspend fun observePushEvents() {
        transport.events.collect { frame ->
            if (frame !is ServerFrame.SubagentsUpdated) return@collect
            // The push carries a fresh canonical `subagents_active` set, so
            // we fold it straight in by replacement rather than re-fetching.
            // Mark initialized so a later first-subscriber doesn't kick off a
            // redundant subagent_list (the cache is already authoritative).
            initialized.set(true)
            state.value = frame.subagentsActive
        }
    }

    private suspend fun observeReconnects() {
        var wasConnected: Boolean? = null
        transport.state.collect { connectionState ->
            val nowConnected = connectionState is ChannelTransport.State.Connected
            if (wasConnected == false && nowConnected && initialized.get()) {
                runCatching { refresh() }
                    .onFailure { e -> Log.w(TAG, "reconnect refresh failed: ${e.message}") }
            }
            wasConnected = nowConnected
        }
    }

    companion object {
        private const val TAG = "SubagentRepository"
    }
}
