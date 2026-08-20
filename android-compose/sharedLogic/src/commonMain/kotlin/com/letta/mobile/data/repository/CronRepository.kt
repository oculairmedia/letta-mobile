package com.letta.mobile.data.repository

import com.letta.mobile.data.model.CronTask
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CompletableDeferred
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
        stateMutex.withLock {
            inFlightRefresh[agentId]?.takeIf { !it.isCompleted }?.let { return it.await() }
        }
        val deferred = CompletableDeferred<Result<List<CronTask>>>()
        stateMutex.withLock {
            val previous = inFlightRefresh.put(agentId, deferred)
            previous?.takeIf { !it.isCompleted }?.cancel()
        }
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
        stateMutex.withLock {
            if (inFlightRefresh[agentId] === deferred) {
                inFlightRefresh.remove(agentId)
            }
        }
        return result
    }

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

    override suspend fun deleteSchedule(agentId: String, taskId: String): Result<Unit> = runCatching {
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
                runCatching { refresh(agentId) }
                    .onFailure { e ->
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
                    runCatching { refresh(agentId) }
                        .onFailure { e ->
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
