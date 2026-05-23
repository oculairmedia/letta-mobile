package com.letta.mobile.data.repository

import android.util.Log
import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.api.ICronRepository
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-lived coroutine scope CronRepository uses for its push observer
 * and reconnect watcher. Defaults to [Dispatchers.IO] + a fresh
 * [SupervisorJob] — same pattern [ChannelTransport] uses for its own
 * background work. Exposed as a factory so tests can substitute a
 * [kotlinx.coroutines.test.TestScope].
 */
internal fun defaultCronScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * letta-mobile-d52f.2: single source of truth for scheduled cron tasks
 * driven by the shim's mobile WS cron protocol (the wire layer landed in
 * [letta-mobile-d52f.1] / [ChannelTransport.sendCronList] etc.).
 *
 * Lifecycle:
 *  - The first subscriber for an `agentId` triggers a `cron_list` WS
 *    round-trip; subsequent subscribers share the same [StateFlow] so
 *    no duplicate fetches fire.
 *  - A `crons_updated` push frame (any reason — local mutation, scheduler
 *    tick, external write) triggers a refresh for every initialized scope.
 *    The shim's current push doesn't carry per-agent scope so we refresh
 *    all observed agents; if that changes the filter can land here.
 *  - On WS reconnect (`Disconnected → Connected` transition) every
 *    initialized scope is refreshed so the UI doesn't keep showing a
 *    stale list after a dropped socket.
 *
 * Mutations (`addSchedule` / `deleteSchedule`) resolve on the matching
 * response frame, not on transport-level send-success: the underlying
 * suspend helpers in [ChannelTransport] already gate on `request_id`
 * correlation. After the response we optimistically patch the local
 * cache so the UI updates without waiting for the `crons_updated` push
 * to round-trip.
 */
@Singleton
open class CronRepository(
    private val transport: IChannelTransport,
    private val scope: CoroutineScope,
) : ICronRepository {
    /**
     * Hilt-friendly constructor — uses a fresh [defaultCronScope] tied
     * to the singleton's lifetime. Tests inject their own scope via the
     * primary constructor.
     */
    @Inject
    constructor(transport: IChannelTransport) : this(transport, defaultCronScope())

    private val stateByAgent = ConcurrentHashMap<String, MutableStateFlow<List<CronTask>>>()
    private val inFlightRefresh = ConcurrentHashMap<String, CompletableDeferred<Result<List<CronTask>>>>()
    // Set of agent ids that have already had their initial cron_list
    // dispatched. The acceptance criterion "repeated subscribe/unsubscribe
    // does not duplicate cron_list calls" is enforced here.
    private val initialized: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        scope.launch { observePushEvents() }
        scope.launch { observeReconnects() }
    }

    /**
     * Hot stream of the scheduled-task list for [agentId]. The flow is
     * shared across all subscribers; only the first subscription per
     * agent triggers a `cron_list` round-trip.
     */
    override fun schedulesFlow(agentId: String): Flow<List<CronTask>> {
        val state = stateFor(agentId)
        if (initialized.add(agentId)) {
            scope.launch { refresh(agentId) }
        }
        return state.asStateFlow()
    }

    /**
     * Force a fresh `cron_list` round-trip for [agentId]. Parallel
     * callers (e.g. the push observer racing a user-initiated pull-to-
     * refresh) share the same in-flight deferred so the shim never sees
     * duplicate `cron_list` frames for the same scope.
     *
     * Returns the resolved task list on success or a failure wrapping
     * the shim's error message (or transport-level exception). Either
     * way callers can branch on `Result.isSuccess`.
     */
    override suspend fun refresh(agentId: String): Result<List<CronTask>> {
        inFlightRefresh[agentId]?.takeIf { !it.isCompleted }?.let { return it.await() }
        val deferred = CompletableDeferred<Result<List<CronTask>>>()
        val previous = inFlightRefresh.put(agentId, deferred)
        previous?.takeIf { !it.isCompleted }?.cancel()
        val result = runCatching {
            val response = transport.sendCronList(agentId = agentId)
            if (!response.success) {
                throw IllegalStateException(response.error ?: "cron_list failed")
            }
            val tasks = response.tasks
            stateFor(agentId).value = tasks
            tasks
        }
        deferred.complete(result)
        inFlightRefresh.remove(agentId, deferred)
        return result
    }

    /**
     * Add a scheduled task. Resolves once the shim returns
     * `cron_add_response` with `success=true`; the resulting [CronTask]
     * is also patched into the local cache so the UI updates without
     * waiting for the `crons_updated` push to round-trip.
     */
    override suspend fun addSchedule(params: CronAddParams): Result<CronTask> = runCatching {
        val response = transport.sendCronAdd(
            agentId = params.agentId,
            name = params.name,
            description = params.description,
            prompt = params.prompt,
            recurring = params.recurring,
            cron = params.cron,
            every = params.every,
            at = params.at,
            timezone = params.timezone,
            conversationId = params.conversationId,
        )
        val task = response.task
        if (!response.success || task == null) {
            throw IllegalStateException(response.error ?: "cron_add failed")
        }
        stateFor(params.agentId).update { current ->
            if (current.any { it.id == task.id }) current else current + task
        }
        task
    }

    /**
     * Delete a scheduled task. Resolves once the shim returns
     * `cron_delete_response` with `success=true`; on success the task is
     * filtered out of the local cache for [agentId].
     */
    override suspend fun deleteSchedule(agentId: String, taskId: String): Result<Unit> = runCatching {
        val response = transport.sendCronDelete(taskId)
        if (!response.success) {
            throw IllegalStateException(response.error ?: "cron_delete failed")
        }
        stateFor(agentId).update { list -> list.filterNot { it.id == taskId } }
        Unit
    }

    private fun stateFor(agentId: String): MutableStateFlow<List<CronTask>> =
        stateByAgent.getOrPut(agentId) { MutableStateFlow(emptyList()) }

    private suspend fun observePushEvents() {
        transport.events.collect { frame ->
            if (frame !is ServerFrame.CronsUpdated) return@collect
            // The shim's current crons_updated payload carries `reason` +
            // `tasks_active` + `at` but no per-agent scope. Refresh every
            // initialized agent rather than only one. If the protocol
            // grows a scope field, narrow this here.
            initialized.toList().forEach { agentId ->
                runCatching { refresh(agentId) }
                    .onFailure { e -> Log.w(TAG, "crons_updated refresh failed for $agentId: ${e.message}") }
            }
        }
    }

    private suspend fun observeReconnects() {
        var wasConnected: Boolean? = null
        transport.state.collect { state ->
            val nowConnected = state is ChannelTransport.State.Connected
            if (wasConnected == false && nowConnected) {
                initialized.toList().forEach { agentId ->
                    runCatching { refresh(agentId) }
                        .onFailure { e -> Log.w(TAG, "reconnect refresh failed for $agentId: ${e.message}") }
                }
            }
            wasConnected = nowConnected
        }
    }

    companion object {
        private const val TAG = "CronRepository"
    }
}

/**
 * Parameter object for [CronRepository.addSchedule] — the client-controlled
 * subset of a [CronTask]. Server-assigned fields (`id`, `status`,
 * `created_at`, `fire_count`, etc.) are filled in by the shim and returned
 * on the `cron_add_response`.
 *
 * Exactly one of [cron] / [every] / [at] should be non-null. The shim
 * normalizes all three into the persisted task's `cron` field.
 */
data class CronAddParams(
    val agentId: String,
    val name: String,
    val description: String,
    val prompt: String,
    val recurring: Boolean,
    val cron: String? = null,
    val every: String? = null,
    val at: String? = null,
    val timezone: String? = null,
    val conversationId: String? = null,
)
