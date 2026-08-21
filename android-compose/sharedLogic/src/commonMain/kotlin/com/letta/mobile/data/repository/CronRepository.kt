package com.letta.mobile.data.repository

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Long-lived coroutine scope CronRepository uses for its push observer
 * and reconnect watcher. Defaults to [Dispatchers.Default] + a fresh
 * [SupervisorJob]. Exposed as a factory so tests can substitute a
 * `kotlinx.coroutines.test.TestScope`.
 */
fun defaultCronScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * letta-mobile-d52f.2: single source of truth for scheduled cron tasks.
 *
 * letta-mobile-lgns8.10.4.1 — TRANSPORT: this repository speaks only the
 * common [IChannelTransport] cron surface (`sendCronList` / `sendCronAdd` /
 * `sendCronDelete`). The concrete wire format is chosen by whichever transport
 * the session graph bound for the active backend:
 *
 *  - `iroh://` config -> `IrohChannelTransport`, which bridges each of these
 *    calls onto the native `cron.*` admin_rpc methods. No shim frame is ever
 *    emitted.
 *  - shim config      -> `ChannelTransport`, which emits the legacy
 *    `cron_list` / `cron_add` / `cron_delete` shim WS frames.
 *
 * Platform-neutral (commonMain) so Android and Desktop share one impl
 * (Phase 4c).
 */
open class CronRepository(
    private val transport: IChannelTransport,
    private val scope: CoroutineScope = defaultCronScope(),
) : ICronRepository {
    private val stateMutex = Mutex()
    private val stateByAgent = mutableMapOf<String, MutableStateFlow<List<CronTask>>>()
    private val inFlightRefresh = mutableMapOf<String, CompletableDeferred<Result<List<CronTask>>>>()
    private val initialized = mutableSetOf<String>()

    init {
        scope.launch { observePushEvents() }
        scope.launch { observeReconnects() }
    }

    override fun schedulesFlow(agentId: String): Flow<List<CronTask>> {
        val state = stateForUnlocked(agentId)
        scope.launch {
            val shouldRefresh = stateMutex.withLock { initialized.add(agentId) }
            if (shouldRefresh) refresh(agentId)
        }
        return state.asStateFlow()
    }

    override suspend fun refresh(agentId: String): Result<List<CronTask>> {
        // Join any in-flight refresh WITHOUT holding [stateMutex] across await —
        // holding the lock while awaiting deadlocks the owner (it needs the mutex
        // to publish results / clear inFlightRefresh).
        stateMutex.withLock {
            inFlightRefresh[agentId]?.takeIf { !it.isCompleted }
        }?.let { return it.await() }

        val deferred = CompletableDeferred<Result<List<CronTask>>>()
        val lostRace = stateMutex.withLock {
            val existing = inFlightRefresh[agentId]
            if (existing != null && !existing.isCompleted) {
                existing
            } else {
                inFlightRefresh[agentId] = deferred
                null
            }
        }
        if (lostRace != null) {
            return lostRace.await()
        }

        val result = try {
            val response = transport.sendCronList(agentId = agentId)
            if (!response.success) {
                throw IllegalStateException(response.error ?: "cron_list failed")
            }
            val tasks = response.tasks
            stateFor(agentId).value = tasks
            Result.success(tasks)
        } catch (cancelled: CancellationException) {
            // Propagate cancellation; do not complete the shared deferred as
            // Result.failure(CancellationException) — that swallows structured
            // cancellation and leaves waiters / test scopes hanging.
            deferred.cancel(cancelled)
            stateMutex.withLock {
                if (inFlightRefresh[agentId] === deferred) {
                    inFlightRefresh.remove(agentId)
                }
            }
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }

        deferred.complete(result)
        stateMutex.withLock {
            if (inFlightRefresh[agentId] === deferred) {
                inFlightRefresh.remove(agentId)
            }
        }
        return result
    }

    override suspend fun addSchedule(params: CronAddParams): Result<CronTask> =
        runCatchingCancellable {
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

    override suspend fun deleteSchedule(agentId: String, taskId: String): Result<Unit> =
        runCatchingCancellable {
            val response = transport.sendCronDelete(taskId)
            if (!response.success) {
                throw IllegalStateException(response.error ?: "cron_delete failed")
            }
            stateFor(agentId).update { list -> list.filterNot { it.id == taskId } }
        }

    private suspend fun stateFor(agentId: String): MutableStateFlow<List<CronTask>> =
        stateMutex.withLock { stateForUnlocked(agentId) }

    private fun stateForUnlocked(agentId: String): MutableStateFlow<List<CronTask>> =
        stateByAgent.getOrPut(agentId) { MutableStateFlow(emptyList()) }

    private suspend fun observePushEvents() {
        transport.events.collect { frame ->
            if (frame !is ServerFrame.CronsUpdated) return@collect
            val agents = stateMutex.withLock { initialized.toList() }
            agents.forEach { agentId ->
                try {
                    refresh(agentId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Throwable) {
                    Telemetry.event(
                        TAG,
                        "crons_updated.refresh.failed",
                        "agentId" to agentId,
                        "error" to (e.message ?: e::class.simpleName),
                        level = Telemetry.Level.WARN,
                    )
                }
            }
        }
    }

    private suspend fun observeReconnects() {
        var wasConnected: Boolean? = null
        transport.state.collect { state ->
            val nowConnected = state is ChannelTransportState.Connected
            if (wasConnected == false && nowConnected) {
                val agents = stateMutex.withLock { initialized.toList() }
                agents.forEach { agentId ->
                    try {
                        refresh(agentId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Throwable) {
                        Telemetry.event(
                            TAG,
                            "reconnect.refresh.failed",
                            "agentId" to agentId,
                            "error" to (e.message ?: e::class.simpleName),
                            level = Telemetry.Level.WARN,
                        )
                    }
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
 * Like [runCatching], but rethrows [CancellationException] so structured
 * cancellation is not turned into Result.failure.
 */
private suspend inline fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        Result.failure(t)
    }

