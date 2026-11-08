package com.letta.mobile.data.memory

import com.letta.mobile.util.attr
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.backend.AppServerRouteUnavailable
import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
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

/** The memory overview load seam the page controller composes (tests fake it). */
interface MemoryParitySource : AutoCloseable {
    val state: StateFlow<MemoryParityControllerState>
    fun start()
    fun reload()
    fun selectAgent(agentId: String)
}

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
) : MemoryParitySource {
    private val stateFlow = MutableStateFlow(MemoryParityControllerState())
    override val state: StateFlow<MemoryParityControllerState> = stateFlow
    private var loadJob: Job? = null
    private val loadRevision = atomic(0L)
    private var selectedAgentId: AgentId? = null
    private var selectedAgentDetail: Agent? = null
    private var selectedAgentGraphId: Long? = null

    override fun start() {
        if (stateFlow.value.memory.sections.isEmpty()) {
            reload()
        }
    }

    override fun reload() {
        val revision = loadRevision.incrementAndGet()
        val target = selectedAgentId
        loadJob?.cancel()
        loadJob = scope.launch { load(target, revision) }
    }

    override fun selectAgent(agentId: String) {
        val target = AgentId(agentId)
        if (selectedAgentId == target) return
        selectedAgentId = target
        stateFlow.update { it.copy(memory = MemoryParityState(), isLoading = true, errorMessage = null) }
        reload()
    }

    override fun close() {
        loadRevision.incrementAndGet()
        loadJob?.cancel()
    }

    private suspend fun load(requestedAgentId: AgentId?, revision: Long) {
        ensureCurrent(revision)
        stateFlow.update { it.copy(isLoading = true, errorMessage = null) }
        val graph = sessionGraphProvider.current
        val channelState = graph.channelTransport.state.value
        if (selectedAgentGraphId != graph.id) {
            selectedAgentDetail = null
            selectedAgentGraphId = graph.id
        }
        val roster = sectionReader.read("roster", SessionRoute.AGENTS, graph.capabilities) {
            graph.agentRepository.refreshAgentsIfStale(maxAgeMs = maxAgeMs)
            graph.agentRepository.agents.value
        }
        ensureCurrent(revision)
        val selectionId = requestedAgentId
        val agents = roster.value.orEmpty()
        val selectedAgent = resolveAgent(graph, selectionId, agents)
        ensureCurrent(revision)
        selectedAgentDetail = selectedAgent
        val projectionAgents = if (selectedAgent != null && agents.none { it.id == selectedAgent.id }) agents + selectedAgent else agents
        val globalTools = readGlobalTools(graph, roster.loaded, selectionId)
        ensureCurrent(revision)
        val skillsLoaded = selectedAgent != null || globalTools.loaded
        val blocks = readBlocks(graph, selectedAgent, roster.loaded)
        ensureCurrent(revision)
        val schedules = readSchedules(graph, selectedAgent, roster.loaded)
        ensureCurrent(revision)
        val context = readContext(graph, selectedAgent, roster.loaded)
        ensureCurrent(revision)
        val memory = MemoryParityMapper.build(
            agents = projectionAgents,
            selectedAgentId = selectedAgent?.id?.value,
            allTools = globalTools.value.orEmpty(),
            blocks = blocks.value,
            schedules = schedules.value.orEmpty(),
            backendDescriptor = graph.backendDescriptor,
            channelTransportState = channelState,
            contextWindowOverview = context.value,
            availability = MemoryParityAvailability(
                skillsLoaded = skillsLoaded,
                memoryBlocksLoaded = blocks.loaded,
                schedulesLoaded = schedules.loaded,
                contextLoaded = context.loaded,
            ),
        )
        ensureCurrent(revision)
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

    private suspend fun resolveAgent(graph: Graph, requested: AgentId?, agents: List<Agent>): Agent? {
        val cached = selectedAgentDetail?.takeIf { it.id == requested }
        return agents.firstOrNull { it.id == requested } ?: cached ?: requested?.let { id ->
            sectionReader.read("agent", SessionRoute.AGENTS, graph.capabilities) {
                graph.agentRepository.getFreshAgent(id)
            }.value
        }
    }

    private suspend fun readGlobalTools(
        graph: Graph,
        rosterLoaded: Boolean,
        requested: AgentId?,
    ): MemoryParitySectionRead<List<Tool>> {
        if (!rosterLoaded || requested != null) return absent(rosterLoaded, emptyList())
        return sectionReader.read("tools", SessionRoute.TOOLS, graph.capabilities) {
            graph.toolRepository.refreshToolsIfStale(maxAgeMs = maxAgeMs)
            graph.toolRepository.getTools().value
        }
    }

    private suspend fun readBlocks(graph: Graph, agent: Agent?, rosterLoaded: Boolean): MemoryParitySectionRead<List<Block>> =
        agent?.let {
            graph.blockRepository?.let { repository ->
                sectionReader.read("blocks", SessionRoute.BLOCKS, graph.capabilities) { repository.getBlocks(it.id.value) }
            } ?: MemoryParitySectionRead.Loaded(it.coreBlocks)
        } ?: absent(rosterLoaded, emptyList())

    private suspend fun readSchedules(
        graph: Graph,
        agent: Agent?,
        rosterLoaded: Boolean,
    ): MemoryParitySectionRead<List<ScheduledMessage>> = agent?.id?.value?.let { id ->
        sectionReader.read("schedules", SessionRoute.SCHEDULES, graph.capabilities) {
            graph.scheduleRepository.refreshSchedules(id)
            graph.scheduleRepository.getSchedules(id).first()
        }
    } ?: absent(rosterLoaded, emptyList())

    private suspend fun readContext(
        graph: Graph,
        agent: Agent?,
        rosterLoaded: Boolean,
    ): MemoryParitySectionRead<ContextWindowOverview?> = agent?.id?.let { id ->
        sectionReader.read("context", SessionRoute.AGENTS, graph.capabilities) { graph.agentRepository.getContextWindow(id) }
    } ?: absent(rosterLoaded, null)

    private fun <T> absent(rosterLoaded: Boolean, empty: T): MemoryParitySectionRead<T> =
        if (rosterLoaded) MemoryParitySectionRead.Loaded(empty) else MemoryParitySectionRead.Unavailable(DependencyUnavailable("roster"))

    private fun ensureCurrent(revision: Long) {
        if (loadRevision.value != revision) throw CancellationException("Memory target changed")
    }

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
            "section" attr section,
            "exceptionClass" attr exceptionClass,
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

    /**
     * [read] when [capabilities] serve [route]; otherwise the section is
     * unavailable for that reason without calling the session's stand-in for the route.
     */
    suspend fun <T> read(
        section: String,
        route: SessionRoute,
        capabilities: SessionCapabilities,
        block: suspend () -> T,
    ): MemoryParitySectionRead<T> =
        if (capabilities.supports(route)) read(section, block) else MemoryParitySectionRead.Unavailable(AppServerRouteUnavailable(route, section))
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
