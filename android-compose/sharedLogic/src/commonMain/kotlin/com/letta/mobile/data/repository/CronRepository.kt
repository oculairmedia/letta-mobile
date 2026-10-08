package com.letta.mobile.data.repository

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.api.AgentScheduleScope
import com.letta.mobile.data.repository.api.CronResumeTarget
import com.letta.mobile.data.repository.api.CronScheduleRef
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.CronControlTransport
import com.letta.mobile.data.transport.api.CronPauseCommand
import com.letta.mobile.data.transport.api.CronResumeCommand
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
// Intentional process-lifetime scope: this is the injectable default the session graph binds and
// tests replace with a TestScope; the repository itself never creates scopes ad hoc.
@Suppress("NoDetachedCoroutineLifecycle")
fun defaultCronScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

private data class CronTaskStateUpdate(
    val target: CronScheduleRef,
    val newStatus: String,
)

/**
 * Thread-safe cache and in-flight refresh coordinator for agent schedules.
 */
private class CronScheduleStateStore {
    private val mutex = Mutex()
    private val mapLock = SynchronizedObject()
    private val stateByAgent = mutableMapOf<String, MutableStateFlow<List<CronTask>>>()
    private val inFlightRefresh = mutableMapOf<String, CompletableDeferred<Result<List<CronTask>>>>()
    private val initialized = mutableSetOf<String>()

    fun stateForUnlocked(scope: AgentScheduleScope): MutableStateFlow<List<CronTask>> =
        synchronized(mapLock) {
            stateByAgent.getOrPut(scope.agentId) { MutableStateFlow(emptyList()) }
        }

    suspend fun stateFor(scope: AgentScheduleScope): MutableStateFlow<List<CronTask>> =
        mutex.withLock { stateForUnlocked(scope) }

    suspend fun markInitialized(scope: AgentScheduleScope): Boolean =
        mutex.withLock { initialized.add(scope.agentId) }

    suspend fun initializedScopes(): List<AgentScheduleScope> =
        mutex.withLock { initialized.map { AgentScheduleScope(it) } }

    suspend fun existingInFlight(scope: AgentScheduleScope): CompletableDeferred<Result<List<CronTask>>>? =
        mutex.withLock { inFlightRefresh[scope.agentId]?.takeIf { !it.isCompleted } }

    suspend fun claimRefresh(
        scope: AgentScheduleScope,
        deferred: CompletableDeferred<Result<List<CronTask>>>,
    ): CompletableDeferred<Result<List<CronTask>>>? = mutex.withLock {
        val existing = inFlightRefresh[scope.agentId]
        if (existing != null && !existing.isCompleted) {
            existing
        } else {
            inFlightRefresh[scope.agentId] = deferred
            null
        }
    }

    suspend fun completeRefresh(scope: AgentScheduleScope, deferred: CompletableDeferred<Result<List<CronTask>>>) {
        mutex.withLock {
            if (inFlightRefresh[scope.agentId] === deferred) {
                inFlightRefresh.remove(scope.agentId)
            }
        }
    }
}

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
 *  - any other config -> `NoOpChannelTransport`, whose cron calls throw
 *    (the legacy shim WebSocket transport was deleted in g70jb.3).
 *
 * Platform-neutral (commonMain) so Android and Desktop share one impl
 * (Phase 4c).
 */
open class CronRepository(
    private val transport: IChannelTransport,
    private val scope: CoroutineScope = defaultCronScope(),
) : ICronRepository {
    private val store = CronScheduleStateStore()

    init {
        scope.launch { observePushEvents() }
        scope.launch { observeReconnects() }
    }

    override fun schedulesFlow(scope: AgentScheduleScope): Flow<List<CronTask>> {
        val state = store.stateForUnlocked(scope)
        this.scope.launch {
            val shouldRefresh = store.markInitialized(scope)
            if (shouldRefresh) refresh(scope)
        }
        return state.asStateFlow()
    }

    override suspend fun refresh(scope: AgentScheduleScope): Result<List<CronTask>> {
        store.existingInFlight(scope)?.let { return it.await() }

        val deferred = CompletableDeferred<Result<List<CronTask>>>()
        val lostRace = store.claimRefresh(scope, deferred)
        if (lostRace != null) {
            return lostRace.await()
        }

        val result = try {
            val response = transport.sendCronList(agentId = scope.agentId)
            if (!response.success) {
                throw IllegalStateException(response.error ?: "cron_list failed")
            }
            val tasks = response.tasks
            store.stateFor(scope).value = tasks
            Result.success(tasks)
        } catch (cancelled: CancellationException) {
            deferred.cancel(cancelled)
            store.completeRefresh(scope, deferred)
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }

        deferred.complete(result)
        store.completeRefresh(scope, deferred)
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
            store.stateFor(AgentScheduleScope(params.agentId)).update { current ->
                if (current.any { it.id == task.id }) current else current + task
            }
            task
        }

    override suspend fun deleteSchedule(target: CronScheduleRef): Result<Unit> =
        runCatchingCancellable {
            val response = transport.sendCronDelete(target.taskId)
            if (!response.success) {
                throw IllegalStateException(response.error ?: "cron_delete failed")
            }
            store.stateFor(AgentScheduleScope(target.agentId)).update { list -> list.filterNot { it.id == target.taskId } }
        }

    override suspend fun pauseSchedule(target: CronScheduleRef): Result<Unit> =
        applyCronStatusMutation(
            CronTaskStateUpdate(target, com.letta.mobile.data.model.CronTaskStatus.PAUSED),
        ) {
            val control = transport as? CronControlTransport
            val command = CronPauseCommand(taskId = target.taskId)
            control?.sendCronPause(command)?.let { it.success to it.error }
                ?: (false to "Unsupported by transport")
        }

    override suspend fun resumeSchedule(target: CronResumeTarget): Result<Unit> =
        applyCronStatusMutation(
            CronTaskStateUpdate(target.target, com.letta.mobile.data.model.CronTaskStatus.ACTIVE),
        ) {
            val control = transport as? CronControlTransport
            val command = CronResumeCommand(taskId = target.target.taskId, scheduledFor = target.scheduledFor)
            control?.sendCronResume(command)?.let { it.success to it.error }
                ?: (false to "Unsupported by transport")
        }

    private suspend fun applyCronStatusMutation(
        update: CronTaskStateUpdate,
        execute: suspend () -> Pair<Boolean, String?>,
    ): Result<Unit> = runCatchingCancellable {
        val (success, error) = execute()
        if (!success) {
            throw IllegalStateException(error ?: "cron status update failed")
        }
        store.stateFor(AgentScheduleScope(update.target.agentId)).update { list ->
            list.map { if (it.id == update.target.taskId) it.copy(status = update.newStatus) else it }
        }
    }

    private suspend fun observePushEvents() {
        transport.events.collect { frame ->
            if (frame !is ServerFrame.CronsUpdated) return@collect
            val scopes = store.initializedScopes()
            scopes.forEach { scope ->
                try {
                    refresh(scope)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Throwable) {
                    Telemetry.event(
                        TAG,
                        "crons_updated.refresh.failed",
                        "agentId" to scope.agentId,
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
                val scopes = store.initializedScopes()
                scopes.forEach { scope ->
                    try {
                        refresh(scope)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Throwable) {
                        Telemetry.event(
                            TAG,
                            "reconnect.refresh.failed",
                            "agentId" to scope.agentId,
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
