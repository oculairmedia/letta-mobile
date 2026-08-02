package com.letta.mobile.data.memory

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class MemoryParityControllerState(
    val memory: MemoryParityState = MemoryParityState(),
    val agents: List<MemoryParityAgentOption> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

@Immutable
data class MemoryParityAgentOption(
    val id: String,
    val name: String,
)

class MemoryParityController<Graph : SessionRepositoryGraph>(
    private val sessionGraphProvider: SessionRepositoryGraphProvider<Graph>,
    private val scope: CoroutineScope,
    private val sectionReader: MemoryParitySectionReader = MemoryParitySectionReader(),
    private val errorMessageMapper: (Throwable) -> String = ::safeMemoryErrorMessage,
    private val maxAgeMs: Long = DEFAULT_MEMORY_REFRESH_MAX_AGE_MS,
) : AutoCloseable {
    private val stateFlow = MutableStateFlow(MemoryParityControllerState())
    val state: StateFlow<MemoryParityControllerState> = stateFlow
    private var loadJob: Job? = null
    private var selectedAgentId: String? = null
    private var selectedAgentDetail: Agent? = null
    private var selectedAgentGraphId: Long? = null

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
        if (selectedAgentGraphId != graph.id) {
            selectedAgentDetail = null
            selectedAgentGraphId = graph.id
        }
        val roster = sectionReader.read("roster") {
            graph.agentRepository.refreshAgentsIfStale(maxAgeMs = maxAgeMs)
            graph.agentRepository.agents.value
        }
        val selectionId = requestedAgentId ?: selectedAgentId
        val agents = roster.value ?: selectedAgentDetail?.let(::listOf).orEmpty()
        val selectedAgent = agents.resolveSelection(selectionId)
        if (roster.loaded) {
            selectedAgentId = selectedAgent?.id?.value
            selectedAgentDetail = selectedAgent
        }

        val globalTools = if (roster.loaded && selectedAgent == null) {
            sectionReader.read("tools") {
                graph.toolRepository.refreshToolsIfStale(maxAgeMs = maxAgeMs)
                graph.toolRepository.getTools().value
            }
        } else if (roster.loaded) {
            MemoryParitySectionRead.Loaded(emptyList())
        } else {
            MemoryParitySectionRead.Unavailable(DependencyUnavailable("roster"))
        }

        // Agent tools and blocks come from the same cached Agent snapshot.
        val agentSnapshotLoaded = roster.loaded || selectedAgentDetail != null
        val skillsLoaded = if (selectedAgent == null) globalTools.loaded else agentSnapshotLoaded
        val schedules = selectedAgent?.id?.value?.let { agentId ->
            sectionReader.read("schedules") {
                graph.scheduleRepository.refreshSchedules(agentId)
                graph.scheduleRepository.getSchedules(agentId).first()
            }
        } ?: if (roster.loaded) {
            MemoryParitySectionRead.Loaded(emptyList())
        } else {
            MemoryParitySectionRead.Unavailable(DependencyUnavailable("roster"))
        }
        val context = selectedAgent?.id?.let { agentId ->
            sectionReader.read("context") { graph.agentRepository.getContextWindow(agentId) }
        } ?: if (roster.loaded) {
            MemoryParitySectionRead.Loaded(null)
        } else {
            MemoryParitySectionRead.Unavailable(DependencyUnavailable("roster"))
        }

        val memory = MemoryParityMapper.build(
            agents = agents,
            selectedAgentId = selectedAgent?.id?.value,
            allTools = globalTools.value.orEmpty(),
            schedules = schedules.value.orEmpty(),
            backendDescriptor = graph.backendDescriptor,
            channelTransportState = channelState,
            contextWindowOverview = context.value,
            availability = MemoryParityAvailability(
                skillsLoaded = skillsLoaded,
                memoryBlocksLoaded = agentSnapshotLoaded,
                schedulesLoaded = schedules.loaded,
                contextLoaded = context.loaded,
            ),
        )
        stateFlow.value = MemoryParityControllerState(
            memory = memory,
            agents = if (roster.loaded) {
                agents.map { MemoryParityAgentOption(it.id.value, it.name) }
            } else {
                stateFlow.value.agents
            },
            isLoading = false,
            errorMessage = roster.error?.let(errorMessageMapper),
        )
    }

    private fun List<Agent>.resolveSelection(requestedAgentId: String?): Agent? =
        requestedAgentId?.let { requested -> firstOrNull { it.id.value == requested } } ?: firstOrNull()

    private class DependencyUnavailable(section: String) :
        IllegalStateException("$section dependency unavailable")

    private companion object {
        const val DEFAULT_MEMORY_REFRESH_MAX_AGE_MS = 30_000L
    }
}

internal fun safeMemoryErrorMessage(throwable: Throwable): String = when (throwable) {
    is UnsupportedOperationException -> "Memory data is not available on this backend."
    else -> "Memory data could not be loaded."
}

class MemoryParitySectionReader(
    private val warn: (String, String) -> Unit = { section, exceptionClass ->
        Telemetry.event(
            tag = "MemoryOverview",
            name = "section_degraded",
            "section" to section,
            "exceptionClass" to exceptionClass,
            level = Telemetry.Level.WARN,
        )
    },
) {
    suspend fun <T> read(section: String, block: suspend () -> T): MemoryParitySectionRead<T> =
        try {
            MemoryParitySectionRead.Loaded(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            warn(section, t::class.simpleName ?: "Throwable")
            MemoryParitySectionRead.Unavailable(t)
        }
}

sealed interface MemoryParitySectionRead<out T> {
    val loaded: Boolean
    val value: T?
    val error: Throwable?

    data class Loaded<T>(override val value: T) : MemoryParitySectionRead<T> {
        override val loaded = true
        override val error: Throwable? = null
    }

    data class Unavailable(override val error: Throwable) : MemoryParitySectionRead<Nothing> {
        override val loaded = false
        override val value: Nothing? = null
    }
}
