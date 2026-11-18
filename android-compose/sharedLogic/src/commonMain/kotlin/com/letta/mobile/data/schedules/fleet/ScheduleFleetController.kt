package com.letta.mobile.data.schedules.fleet

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.schedules.CronScheduleSource
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.RunRecord
import com.letta.mobile.data.schedules.ScheduleCreateRequest
import com.letta.mobile.data.schedules.ScheduleLibraryOperations
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.util.AgentFleetReads
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the fleet's schedules come from: the roster, each agent's native schedules and crons, and run records. */
class ScheduleFleetSources(
    val agents: IAgentRepository,
    val schedules: IScheduleRepository,
    /** The session's cron source (it also controls the crons); null when the host serves none. */
    val crons: CronScheduleSource? = null,
    /** Recorded runs for an agent's schedules; null when the host keeps none. */
    val runHistory: (suspend (AgentId, List<String>) -> List<RunRecord>)? = null,
)

/** How fleet reads are judged: failure messages, and whether the native schedule route is served. */
class ScheduleFleetPolicy(
    val errorMessage: (Throwable, String) -> String = { throwable, fallback -> throwable.message ?: fallback },
    /** True for a failure meaning the host does not serve the native schedule route. */
    val nativeUnavailable: (Throwable) -> Boolean = { false },
    /** False when the session's capabilities already say the native route is not served. */
    val nativeRoute: Boolean = true,
)

/**
 * The Schedules page with no agent focused: every agent's schedules, read a few agents at a time.
 * One agent's failed read leaves the others listed (the page notes how many failed); only every
 * read failing is an error. Rows keep their own agent, so deletes, pause / resume and run history
 * go through it, and a new schedule is made for the agent the form names. Its state is a
 * [ScheduleLibraryState] with [ScheduleLibraryState.fleet] set, so the page renders it like one
 * agent's library.
 */
class ScheduleFleetController(
    private val sources: ScheduleFleetSources,
    private val policy: ScheduleFleetPolicy,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private val operations = ScheduleLibraryOperations(
        sources.agents, sources.schedules, sources.crons, policy.errorMessage, policy.nativeUnavailable,
    )
    private val stateFlow = MutableStateFlow(ScheduleLibraryState(fleet = true))
    val state: StateFlow<ScheduleLibraryState> = stateFlow

    private var loadJob: Job? = null
    private var mutationJob: Job? = null

    /** Latched once every agent answered and none on the native route, so later reads skip it. */
    private var nativeUnserved = !policy.nativeRoute

    /** Reads every agent's schedules again; the rows on screen stay until the new ones arrive. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch { load() }
    }

    /** Narrows the rows to [agent]'s; null shows every agent's again. */
    fun filterAgent(agent: AgentId?) {
        stateFlow.update { it.copy(agentFilter = agent?.value) }
    }

    /** Makes the schedule for the agent the form chose; a request naming no agent does nothing. */
    fun createSchedule(request: ScheduleCreateRequest) {
        val agent = request.agentId ?: return
        mutate("Failed to create schedule") { sources.schedules.createSchedule(agent, request.toCreateParams()) }
    }

    /** Deletes a native schedule through its own agent. */
    fun deleteNative(schedule: ScheduledMessage) {
        mutate("Failed to delete schedule") { sources.schedules.deleteSchedule(schedule.agentId, schedule.id) }
    }

    /** Deletes a cron through its own agent; a host without cron control does nothing. */
    fun deleteCron(task: CronTask) {
        val repository = sources.crons?.repository ?: return
        val agent = task.agentId ?: return stateFlow.update { it.copy(errorMessage = NO_CRON_OWNER) }
        mutate("Failed to delete schedule") { repository.deleteSchedule(agent, task.id).getOrThrow() }
    }

    /**
     * Pauses or resumes a cron through its own agent, once at a time; the row stays until the host
     * lists that agent's crons again.
     */
    fun toggleCronPause(task: CronTask, resume: Boolean) {
        val repository = sources.crons?.repository ?: return
        val agent = task.agentId?.let(::AgentId) ?: return
        if (stateFlow.value.pendingCronIds.isNotEmpty()) return
        mutationJob?.cancel()
        stateFlow.update { it.copy(pendingCronIds = setOf(task.id), errorMessage = null) }
        mutationJob = scope.launch { changePause(repository, agent, task, resume) }
    }

    override fun close() {
        loadJob?.cancel()
        mutationJob?.cancel()
    }

    private suspend fun load() {
        stateFlow.update { it.copy(isLoading = true, errorMessage = null) }
        val (agents, rosterError) = operations.loadAgentRoster { stateFlow.value.agents }
        val read = AgentFleetReads.read(agents.map { it.id }) { agent -> readAgent(agent) }
        if (sources.crons != null && read.nativeRouteUnserved()) nativeUnserved = true
        stateFlow.update { current ->
            val published = current.fleetTarget(agents).withFleetRead(read, policy.errorMessage)
            published.copy(
                errorMessage = published.errorMessage ?: rosterError?.let { policy.errorMessage(it, "Failed to load agents") },
            )
        }
        loadRunHistory()
    }

    /** One agent's rows; a failed cron read loses the agent only when it has no native schedules to show. */
    private suspend fun readAgent(agent: AgentId): AgentScheduleRead {
        val native = if (nativeUnserved) null else nativeOrNull(agent)
        val crons = runCatchingCancellable { sources.crons?.listCrons(agent) }
        if (native == null) crons.getOrThrow()
        return AgentScheduleRead(native, crons.getOrNull(), crons.exceptionOrNull())
    }

    /** The agent's native schedules, or null when the host does not serve the route. */
    private suspend fun nativeOrNull(agent: AgentId): List<ScheduledMessage>? {
        val result = runCatchingCancellable { operations.loadSchedulesForAgent(agent) }
        val failure = result.exceptionOrNull() ?: return result.getOrThrow()
        if (policy.nativeUnavailable(failure)) return null
        throw failure
    }

    /** Each agent's recorded runs for its own rows, merged; an agent whose history fails shows none. */
    private suspend fun loadRunHistory() {
        val source = sources.runHistory ?: return
        val state = stateFlow.value
        val owned = state.schedules.map { it.agentId to it.id } + state.crons.mapNotNull { cron -> cron.agentId?.let { it to cron.id } }
        val idsByAgent = owned.groupBy({ AgentId(it.first) }, { it.second })
        val read = AgentFleetReads.read(idsByAgent.keys.toList()) { agent -> source(agent, idsByAgent.getValue(agent).distinct()) }
        stateFlow.update { current -> current.copy(runHistory = read.values.flatMap { it.second }.groupBy { it.scheduleId }) }
    }

    /** Runs one mutation, then reads the fleet again; a failure is reported and the rows stay. */
    private fun mutate(failure: String, work: suspend () -> Unit) {
        mutationJob?.cancel()
        mutationJob = scope.launch {
            runCatchingCancellable { work() }.fold(
                onSuccess = { refresh() },
                onFailure = { stateFlow.value = operations.mutationFailure(it, stateFlow.value, failure) },
            )
        }
    }

    /** The pending mark clears however the change ends, cancellation included, so later controls work. */
    private suspend fun changePause(repository: ICronRepository, agent: AgentId, task: CronTask, resume: Boolean) {
        try {
            val result = runCatchingCancellable {
                if (resume) repository.resumeSchedule(agent.value, task.id).getOrThrow()
                else repository.pauseSchedule(agent.value, task.id).getOrThrow()
                sources.crons?.listCrons(agent).orEmpty()
            }
            stateFlow.update { current ->
                result.fold(
                    onSuccess = { crons -> current.copy(crons = current.crons.replacingAgentCrons(agent, crons, current.agents)) },
                    onFailure = { current.copy(errorMessage = policy.errorMessage(it, "Failed to change schedule state")) },
                )
            }
        } finally {
            stateFlow.update { it.copy(pendingCronIds = emptySet()) }
        }
    }
}

private const val NO_CRON_OWNER = "This schedule has no agent to delete it through; focus an agent to manage it."
