package com.letta.mobile.data.session.graph

import com.letta.mobile.data.backend.SessionCapabilities
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.repository.api.IAgentBlockRepository
import com.letta.mobile.data.repository.api.IAgentRepository
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
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.runtime.BackendDescriptor
import com.letta.mobile.runtime.LettaBackend
import kotlinx.atomicfu.atomic

/**
 * letta-mobile-by4vm.86: the repositories a host binds itself for one graph generation — its own
 * implementations, or an `unavailable(route)` stand-in where the host serves no such route. The
 * transport-bound ones come from [SharedSessionGraph.transportBound] instead.
 */
class HostSessionRepositories(
    val agentRepository: IAgentRepository,
    val blockRepository: IAgentBlockRepository?,
    val conversationRepository: IConversationRepository,
    val mcpServerRepository: IMcpServerRepository,
    val modelRepository: IModelRepository,
    val passageRepository: IPassageRepository,
    val projectRepository: IProjectRepository,
    val projectWorkRepository: IProjectWorkRepository,
    val runRepository: IRunRepository,
    val providerRepository: IProviderRepository,
    val scheduleRepository: IScheduleRepository,
    val stepRepository: IStepRepository,
    val toolRepository: IToolRepository,
    val vibesyncEventStreamRepository: IVibesyncEventStreamRepository,
)

/** Which generation a graph is and what it is bound to. */
class SessionGraphIdentity(
    val id: Long,
    val backendDescriptor: BackendDescriptor,
    val localRuntimeBackend: LettaBackend?,
    val channelTransport: IChannelTransport,
    val capabilities: SessionCapabilities,
)

/**
 * letta-mobile-by4vm.86: the one [SessionRepositoryGraph] implementation every host uses. Every
 * member is interface-typed; Android (`SessionGraph`, which adds its platform-bound extras by
 * delegation) and desktop build it from [HostSessionRepositories] plus
 * [SharedSessionGraph.transportBound], so the two can no longer drift field by field.
 *
 * [close] runs [onClose] exactly once; [isClosed] reports whether it has.
 */
class SharedSessionRepositoryGraph(
    identity: SessionGraphIdentity,
    host: HostSessionRepositories,
    transportBound: TransportBoundSessionRepositories,
    private val onClose: () -> Unit = {},
) : SessionRepositoryGraph {
    override val id: Long = identity.id
    override val backendDescriptor: BackendDescriptor = identity.backendDescriptor
    override val localRuntimeBackend: LettaBackend? = identity.localRuntimeBackend
    override val channelTransport: IChannelTransport = identity.channelTransport
    override val capabilities: SessionCapabilities = identity.capabilities

    override val agentRepository: IAgentRepository = host.agentRepository
    override val blockRepository: IAgentBlockRepository? = host.blockRepository
    override val conversationRepository: IConversationRepository = host.conversationRepository
    override val mcpServerRepository: IMcpServerRepository = host.mcpServerRepository
    override val modelRepository: IModelRepository = host.modelRepository
    override val passageRepository: IPassageRepository = host.passageRepository
    override val projectRepository: IProjectRepository = host.projectRepository
    override val projectWorkRepository: IProjectWorkRepository = host.projectWorkRepository
    override val runRepository: IRunRepository = host.runRepository
    override val providerRepository: IProviderRepository = host.providerRepository
    override val scheduleRepository: IScheduleRepository = host.scheduleRepository
    override val stepRepository: IStepRepository = host.stepRepository
    override val toolRepository: IToolRepository = host.toolRepository
    override val vibesyncEventStreamRepository: IVibesyncEventStreamRepository = host.vibesyncEventStreamRepository

    override val cronRepository: ICronRepository = transportBound.cronRepository
    override val selfTodoRepository: ISelfTodoRepository = transportBound.selfTodoRepository
    override val subagentRepository: ISubagentRepository = transportBound.subagentRepository

    /** letta-mobile-r2zo8: each conversation's latest context total, fed by [channelTransport]. */
    val contextTokenReadings: ContextTokenReadings = transportBound.contextTokenReadings

    private val closed = atomic(false)

    val isClosed: Boolean
        get() = closed.value

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) onClose()
    }
}
