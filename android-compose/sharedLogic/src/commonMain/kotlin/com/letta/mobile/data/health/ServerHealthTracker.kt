package com.letta.mobile.data.health

import com.letta.mobile.data.model.LettaConfig
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the tracker reports after each probe (Android logs it to telemetry). */
fun interface ServerHealthListener {
    fun onProbed(config: LettaConfig, health: ServerHealthState)
}

/**
 * letta-mobile-qmxn / letta-mobile-ckmub.11: the saved backends' online dots, shared by every host.
 *
 * Probes each of [configs] with [probe] and exposes the result per config id. Re-probing fires (a)
 * whenever the set of configured ids changes (a rename alone does not burn a round trip) and (b) on
 * demand through [refreshAll] from a picker opening. There is no periodic background poll (the user
 * opted out of that on letta-mobile-qmxn). While a probe runs its row reads [ServerHealthState.PROBING],
 * so the dot never flips straight from a stale answer.
 *
 * The config observer runs in [scope] on [context] for the tracker's whole life.
 */
class ServerHealthTracker(
    private val configs: StateFlow<List<LettaConfig>>,
    private val probe: ServerHealthProbe,
    scope: CoroutineScope,
    context: CoroutineContext = EmptyCoroutineContext,
    private val listener: ServerHealthListener = ServerHealthListener { _, _ -> },
) : IServerHealthRepository {
    private val statesFlow = MutableStateFlow<Map<String, ServerHealthState>>(emptyMap())
    override val states: StateFlow<Map<String, ServerHealthState>> = statesFlow.asStateFlow()

    private val refreshMutex = Mutex()

    init {
        scope.launch(context) {
            configs
                .distinctUntilChangedBy { list -> list.map { it.id }.toSet() }
                .collect { refresh(it) }
        }
    }

    override suspend fun refreshAll() {
        refresh(configs.value)
    }

    private suspend fun refresh(current: List<LettaConfig>) {
        if (current.isEmpty()) {
            statesFlow.value = emptyMap()
            return
        }
        refreshMutex.withLock {
            val liveIds = current.map { it.id }.toSet()
            statesFlow.update { states ->
                states.filterKeys { it in liveIds } + current.associate { it.id to ServerHealthState.PROBING }
            }
            coroutineScope {
                current.map { config -> async { record(config, probe.probe(config)) } }.awaitAll()
            }
        }
    }

    private fun record(config: LettaConfig, health: ServerHealthState) {
        statesFlow.update { it + (config.id to health) }
        listener.onProbed(config, health)
    }
}
