package com.letta.mobile.data.memory

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryParityControllerState(
    val memory: MemoryParityState = MemoryParityState(),
    val agents: List<MemoryParityAgentOption> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class MemoryParityAgentOption(
    val id: String,
    val name: String,
)

class MemoryParityController<Graph : SessionRepositoryGraph>(
    private val sessionGraphProvider: SessionRepositoryGraphProvider<Graph>,
    private val scope: CoroutineScope,
    private val errorMessageMapper: (Throwable) -> String = { throwable ->
        throwable.message ?: "Memory data could not be loaded."
    },
    private val maxAgeMs: Long = DEFAULT_MEMORY_REFRESH_MAX_AGE_MS,
) : AutoCloseable {
    private val stateFlow = MutableStateFlow(MemoryParityControllerState())
    val state: StateFlow<MemoryParityControllerState> = stateFlow
    private var loadJob: Job? = null
    private var selectedAgentId: String? = null

    fun start() {
        if (stateFlow.value.memory.sections.isEmpty()) {
            reload()
        }
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch { load(selectedAgentId) }
    }

    fun selectAgent(agentId: String) {
        if (selectedAgentId == agentId) return
        selectedAgentId = agentId
        reload()
    }

    override fun close() {
        loadJob?.cancel()
    }

    private suspend fun load(requestedAgentId: String?) {
        stateFlow.update { it.copy(isLoading = true, errorMessage = null) }
        val graph = sessionGraphProvider.current
        val channelState = graph.channelTransport.state.value
        try {
            graph.agentRepository.refreshAgentsIfStale(maxAgeMs = maxAgeMs)
            val agents = graph.agentRepository.agents.value
            val selectedAgent = agents.resolveSelection(requestedAgentId)
            selectedAgentId = selectedAgent?.id?.value

            val allTools = recoverOrDefault(emptyList()) {
                graph.toolRepository.refreshToolsIfStale(maxAgeMs = maxAgeMs)
                graph.toolRepository.getTools().value
            }

            val schedules = selectedAgent?.id?.value?.let { agentId ->
                recoverOrDefault(emptyList()) {
                    graph.scheduleRepository.refreshSchedules(agentId)
                    graph.scheduleRepository.getSchedules(agentId).first()
                }
            }.orEmpty()

            val contextWindow = selectedAgent?.id?.let { agentId ->
                recoverOrDefault(null) { graph.agentRepository.getContextWindow(agentId) }
            }

            val memory = MemoryParityMapper.build(
                agents = agents,
                selectedAgentId = selectedAgent?.id?.value,
                allTools = allTools,
                schedules = schedules,
                backendDescriptor = graph.backendDescriptor,
                channelTransportState = channelState,
                contextWindowOverview = contextWindow,
            )
            stateFlow.value = MemoryParityControllerState(
                memory = memory,
                agents = agents.map { MemoryParityAgentOption(it.id.value, it.name) },
                isLoading = false,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val fallback = MemoryParityMapper.build(
                agents = emptyList(),
                selectedAgentId = null,
                allTools = emptyList(),
                schedules = emptyList(),
                backendDescriptor = graph.backendDescriptor,
                channelTransportState = channelState,
                contextWindowOverview = null,
            )
            stateFlow.value = MemoryParityControllerState(
                memory = fallback,
                isLoading = false,
                errorMessage = errorMessageMapper(t),
            )
        }
    }

    private fun List<Agent>.resolveSelection(requestedAgentId: String?): Agent? =
        if (requestedAgentId != null) {
            firstOrNull { it.id.value == requestedAgentId }
        } else {
            firstOrNull()
        }

    private suspend fun <T> recoverOrDefault(
        defaultValue: T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            defaultValue
        }

    private companion object {
        const val DEFAULT_MEMORY_REFRESH_MAX_AGE_MS = 30_000L
    }
}
