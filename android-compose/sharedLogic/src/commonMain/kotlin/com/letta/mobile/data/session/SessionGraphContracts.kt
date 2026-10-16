package com.letta.mobile.data.session

import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.repository.api.IMcpServerRepository
import com.letta.mobile.data.repository.api.IModelRepository
import com.letta.mobile.data.repository.api.IPassageRepository
import com.letta.mobile.data.repository.api.IProjectRepository
import com.letta.mobile.data.repository.api.IProjectWorkRepository
import com.letta.mobile.data.repository.api.IProviderRepository
import com.letta.mobile.data.repository.api.IRunRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.repository.api.ISelfTodoRepository
import com.letta.mobile.data.repository.api.IStepRepository
import com.letta.mobile.data.repository.api.ISubagentRepository
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.api.IVibesyncEventStreamRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.runtime.BackendDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * KMP-safe session graph surface for chat, timeline, and runtime wiring.
 *
 * Android-only list/paging repositories remain platform-side until a paging
 * neutral contract exists; this graph intentionally includes only contracts
 * already safe for commonMain consumers.
 *
 * Hosts obtain graphs via [SessionRepositoryGraphFactory] and hold them in a
 * [SessionRepositoryGraphProvider] — prefer [DefaultSessionRepositoryGraphProvider]
 * so Android and Desktop share rebuild / switch semantics.
 */
interface SessionRepositoryGraph {
    val id: Long
    val backendDescriptor: BackendDescriptor
    val agentRepository: IAgentRepository
    val blockRepository: IAgentBlockRepository?
        get() = null
    val channelTransport: IChannelTransport
    val conversationRepository: IConversationRepository
    val cronRepository: ICronRepository
    val mcpServerRepository: IMcpServerRepository
    val modelRepository: IModelRepository
    val passageRepository: IPassageRepository
    val projectRepository: IProjectRepository
    val projectWorkRepository: IProjectWorkRepository
    val runRepository: IRunRepository
    val providerRepository: IProviderRepository
    val scheduleRepository: IScheduleRepository
    val selfTodoRepository: ISelfTodoRepository
    val stepRepository: IStepRepository
    val subagentRepository: ISubagentRepository
    val toolRepository: IToolRepository
    val vibesyncEventStreamRepository: IVibesyncEventStreamRepository

    /**
     * letta-mobile-by4vm.13: which optional routes this graph serves. A repository for a route it
     * does not serve throws [com.letta.mobile.data.backend.AppServerRouteUnavailable] on every call,
     * so ask here (or use [ifSupported]) before touching one.
     */
    val capabilities: SessionCapabilities

    fun close()
}

/** [read] when this graph serves [route]; null (render "not available") when it does not. */
inline fun <T> SessionRepositoryGraph.ifSupported(route: SessionRoute, read: SessionRepositoryGraph.() -> T): T? =
    if (capabilities.supports(route)) read() else null

/** The agent roster, or an empty one (a new flow; remember it per graph) where no agents are served. */
fun SessionRepositoryGraph.agentRosterOrEmpty(): StateFlow<List<Agent>> =
    ifSupported(SessionRoute.AGENTS) { agentRepository.agents } ?: MutableStateFlow(emptyList())

/** The LLM catalog, or an empty one (a new flow; remember it per graph) where no models are served. */
fun SessionRepositoryGraph.llmModelsOrEmpty(): StateFlow<List<LlmModel>> =
    ifSupported(SessionRoute.MODELS) { modelRepository.llmModels } ?: MutableStateFlow(emptyList())

interface SessionRepositoryGraphFactory<out Graph : SessionRepositoryGraph> {
    fun create(): Graph
}

interface SessionRepositoryGraphProvider<Graph : SessionRepositoryGraph> {
    val currentGraph: StateFlow<Graph>
    val sessionError: StateFlow<Throwable?>
    val current: Graph

    fun rebuild(): Graph

    suspend fun <T> withCurrentSession(block: suspend (Graph) -> T): T
}
