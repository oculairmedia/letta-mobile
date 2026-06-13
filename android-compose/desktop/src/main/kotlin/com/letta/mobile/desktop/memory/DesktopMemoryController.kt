package com.letta.mobile.desktop.memory

import com.letta.mobile.data.memory.MemoryParityMapper
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.desktop.data.DesktopRepositoryUnavailableException
import com.letta.mobile.desktop.data.DesktopSessionGraphProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DesktopMemorySurfaceState(
    val memory: MemoryParityState = MemoryParityState(),
    val agents: List<DesktopMemoryAgentOption> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class DesktopMemoryAgentOption(
    val id: String,
    val name: String,
)

class DesktopMemoryController(
    private val sessionGraphProvider: DesktopSessionGraphProvider,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val stateFlow = MutableStateFlow(DesktopMemorySurfaceState())
    val state: StateFlow<DesktopMemorySurfaceState> = stateFlow
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
            graph.agentRepository.refreshAgentsIfStale(maxAgeMs = MEMORY_REFRESH_MAX_AGE_MS)
            val agents = graph.agentRepository.agents.value
            val selectedAgent = agents.resolveSelection(requestedAgentId)
            selectedAgentId = selectedAgent?.id?.value

            val allTools = runCatching {
                graph.toolRepository.refreshToolsIfStale(maxAgeMs = MEMORY_REFRESH_MAX_AGE_MS)
                graph.toolRepository.getTools().value
            }.getOrDefault(emptyList())

            val schedules = selectedAgent?.id?.value?.let { agentId ->
                runCatching {
                    graph.scheduleRepository.refreshSchedules(agentId)
                    graph.scheduleRepository.getSchedules(agentId).first()
                }.getOrDefault(emptyList())
            }.orEmpty()

            val contextWindow = selectedAgent?.id?.let { agentId ->
                runCatching { graph.agentRepository.getContextWindow(agentId) }.getOrNull()
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
            stateFlow.value = DesktopMemorySurfaceState(
                memory = memory,
                agents = agents.map { DesktopMemoryAgentOption(it.id.value, it.name) },
                isLoading = false,
            )
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
            stateFlow.value = DesktopMemorySurfaceState(
                memory = fallback,
                isLoading = false,
                errorMessage = t.toDesktopMemoryMessage(),
            )
        }
    }

    private fun List<Agent>.resolveSelection(requestedAgentId: String?): Agent? =
        firstOrNull { it.id.value == requestedAgentId } ?: firstOrNull()

    private fun Throwable.toDesktopMemoryMessage(): String =
        when (this) {
            is DesktopRepositoryUnavailableException -> "Desktop memory repositories are not available for this backend yet."
            else -> message ?: this::class.simpleName ?: "Memory data could not be loaded."
        }

    private companion object {
        const val MEMORY_REFRESH_MAX_AGE_MS = 30_000L
    }
}
