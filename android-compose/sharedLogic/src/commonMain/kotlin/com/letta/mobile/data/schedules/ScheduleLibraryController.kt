package com.letta.mobile.data.schedules

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.schedules.control.ScheduleIdentity
import com.letta.mobile.data.schedules.control.ScheduleRevision
import com.letta.mobile.data.schedules.leases.Lease
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.ICronRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
import com.letta.mobile.data.fleet.AgentFleetRead
import com.letta.mobile.data.fleet.AgentFleetReads
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
data class ScheduleLibraryState(
    /**
     * Slim agent projection for the picker dropdown — only id+name+description
     * are needed to render the list and select by id. Sourced via
     * [IAgentRepository.listAgentSummaries] (the admin-shim's
     * `GET /v1/agents?slim=true`), NOT the full ~621KB agents payload.
     */
    val agents: List<AgentSummary> = emptyList(),
    val selectedAgentId: String? = null,
    val schedules: List<ScheduledMessage> = emptyList(),
    val scheduleAdminAvailable: Boolean = true,
    val scheduleAdminMessage: String? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    /**
     * Cron tasks served by the backend's `/v1/crons` route, used as a
     * fallback for backends that don't serve the Letta-native
     * `/v1/agents/{id}/schedule` admin route (e.g. self-hosted servers
     * behind the admin-shim). When [cronMode] is true the UI renders
     * these instead of [schedules]. Mirrors the desktop schedules surface.
     */
    val crons: List<CronTask> = emptyList(),
    /**
     * True when this backend is cron-backed: the native schedule route was
     * unavailable but the cron route answered. An empty cron list in this
     * mode is a real "0 schedules", NOT a signal that admin is unavailable.
     */
    val cronMode: Boolean = false,
    /** Recorded runs keyed by their cron task ID. */
    val runHistory: Map<String, List<RunRecord>> = emptyMap(),
    /** Read-only leases owned by the selected agent. */
    val leases: List<Lease> = emptyList(),
    /** Cron controls waiting for host confirmation and the refreshed list. */
    val pendingCronIds: Set<String> = emptySet(),
    /**
     * True when no agent is focused and the library holds every agent's schedules (rows carry
     * their own agent); [selectedAgentId] is null then.
     */
    val fleet: Boolean = false,
    /** In [fleet] mode, the one agent whose schedules the page narrows to; null shows every agent's. */
    val agentFilter: String? = null,
) {
    /** The agent whose schedules show: the selected one, else the fleet's filter; null shows all. */
    val scopeAgentId: String? get() = selectedAgentId ?: agentFilter.takeIf { fleet }

    /** Crons scoped to the selected agent (cron tasks may carry no agent id). */
    val cronsForSelectedAgent: List<CronTask>
        get() = selectedAgentId?.let { agentId ->
            crons.filter { it.agentId == null || it.agentId == agentId }
        } ?: crons

    val displayItems: List<ScheduleLibraryItem>
        get() = schedules.map { it.toScheduleLibraryItem() }

    val emptyMessage: String
        get() = when {
            !scheduleAdminAvailable && !cronMode -> scheduleAdminMessage ?: SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE
            fleet && agents.isEmpty() -> "No agents available."
            fleet -> "No schedules across your agents."
            cronMode -> "No schedules for this agent."
            selectedAgentId == null -> "No agents available."
            else -> "No schedules for this agent."
        }
}

@Immutable
data class ScheduleLibraryItem(
    val schedule: ScheduledMessage,
    val messagePreview: String,
    val timing: ScheduleTiming,
)

@Immutable
sealed interface ScheduleTiming {
    data class Recurring(val cronExpression: String) : ScheduleTiming
    data class OneTime(val displayTime: String) : ScheduleTiming
}

fun ScheduledMessage.toScheduleLibraryItem(): ScheduleLibraryItem =
    ScheduleLibraryItem(
        schedule = this,
        messagePreview = message.messages.firstOrNull()?.content.orEmpty(),
        timing = if (schedule.type == "recurring") {
            ScheduleTiming.Recurring(schedule.cronExpression.orEmpty())
        } else {
            ScheduleTiming.OneTime(nextScheduledTime ?: schedule.scheduledAt?.toPortableScheduleTime().orEmpty())
        },
    )

private fun Double.toPortableScheduleTime(): String =
    if (isFinite() && this % 1.0 == 0.0) toLong().toString() else toString()

class ScheduleLibraryController(
    private val agentRepository: IAgentRepository,
    private val scheduleRepository: IScheduleRepository,
    private val scope: CoroutineScope,
    private val errorMessageMapper: (Throwable, String) -> String = { throwable, fallback ->
        throwable.message ?: fallback
    },
    private val scheduleAdminUnavailableMatcher: (Throwable) -> Boolean = { false },
    /**
     * Optional cron-backed fallback. When the native schedule route is
     * unavailable (matched by [scheduleAdminUnavailableMatcher]), the
     * controller lists crons via this source (the backend's `/v1/crons`
     * route) instead of dead-ending on the "admin unavailable" state.
     * Parity with the desktop schedules surface. Defaults to null, which
     * preserves the legacy behaviour for callers that don't wire it.
     */
    private val cronSource: CronScheduleSource? = null,
    /** Initial query target supplied by the caller; roster membership never replaces it. */
    private val initialAgentId: String? = null,
    /**
     * False when the session's capabilities say it serves no native schedule
     * route, so the controller never makes the doomed call and opens on the cron fallback or the
     * "admin unavailable" state straight away.
     */
    nativeScheduleRoute: Boolean = true,
    /** Optional shared wrapper readers for run history and read-only leases. */
    private val recordSources: ScheduleRecordSources? = null,
) : AutoCloseable {
    private val cronRepository: ICronRepository? = cronSource?.repository
    private val operations = ScheduleLibraryOperations(
        agentRepository, scheduleRepository, cronSource, errorMessageMapper, scheduleAdminUnavailableMatcher,
    )

    private val stateFlow = MutableStateFlow(ScheduleLibraryState())
    val state: StateFlow<ScheduleLibraryState> = stateFlow

    private var loadJob: Job? = null
    private var mutationJob: Job? = null
    private var cronMutationJob: Job? = null
    private val targetRevision = atomic(0L)

    /**
     * Per-controller-instance verdict: once the native
     * `/v1/agents/{id}/schedule` route has answered with an
     * "admin unavailable" error (404/405/501, matched by
     * [scheduleAdminUnavailableMatcher]) AND a [cronSource] is wired, we
     * latch this to true and stop re-attempting the doomed native round-trip
     * on subsequent loads/selects. Every later load goes straight to the
     * cron source. This is intentionally NOT persisted: a fresh controller
     * starts Unknown (false) and probes the native route once, so switching
     * to a backend that serves the native route re-probes from scratch.
     *
     * Backends that DO serve the native route never latch this — the native
     * path is used on every load, so their behaviour is completely unchanged.
     */
    private var nativeScheduleRouteUnavailable = !nativeScheduleRoute

    fun start() {
        if (stateFlow.value.agents.isEmpty() && stateFlow.value.schedules.isEmpty()) {
            if (initialAgentId != null && stateFlow.value.selectedAgentId == null) {
                stateFlow.update { it.copy(selectedAgentId = initialAgentId) }
            }
            loadData()
        }
    }

    fun loadData() {
        loadJob?.cancel()
        val revision = ScheduleRevision(targetRevision.value)
        loadJob = scope.launch { if (stateFlow.value.fleet) loadFleet(revision) else loadTarget(revision) }
    }

    /** Loads the selected agent's schedules (none selected: the roster only). */
    private suspend fun loadTarget(revision: ScheduleRevision) {
        stateFlow.update { it.copy(isLoading = true, errorMessage = null) }
        val (agents, rosterError) = operations.loadAgentRoster { stateFlow.value.agents }
        if (targetRevision.value != revision.value) return
        val target = stateFlow.value.targetForAgents(agents)
        try {
            if (nativeScheduleRouteUnavailable) {
                publishCronFallbackOrUnavailable(target, revision)
                return
            }
            val schedules = operations.loadTargetSchedules(target)
            val crons = operations.loadControlCrons(target.selectedAgentId?.let(::AgentId))
            if (targetRevision.value != revision.value) return
            stateFlow.value = target.withSchedules(schedules).withControlCrons(crons).copy(
                errorMessage = rosterError?.let { errorMessageMapper(it, "Failed to load agents") },
            )
            loadRunHistory()
            loadLeases()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (targetRevision.value != revision.value) return
            publishLoadFailure(t, target, revision) {
                stateFlow.update { current ->
                    current.copy(
                        agents = target.agents,
                        isLoading = false,
                        errorMessage = errorMessageMapper(t, "Failed to load schedules"),
                    )
                }
                loadLeases()
            }
        }
    }

    private suspend fun publishLoadFailure(
        error: Throwable,
        target: ScheduleLibraryState,
        revision: ScheduleRevision,
        onFailure: suspend () -> Unit,
    ) {
        if (scheduleAdminUnavailableMatcher(error)) {
            // Only cron-backed controllers retain the unavailable verdict between queries.
            if (cronSource != null || cronRepository != null) nativeScheduleRouteUnavailable = true
            publishCronFallbackOrUnavailable(target, revision)
        } else {
            onFailure()
        }
    }

    /**
     * Shows every agent's schedules (no agent focused): each agent is read a few at a time, one
     * agent's failure leaves the others listed, and rows keep their own agent for mutations.
     */
    fun selectFleet() {
        loadJob?.cancel()
        val current = stateFlow.value
        if (!current.fleet || current.selectedAgentId != null) {
            targetRevision.incrementAndGet()
            cronMutationJob?.cancel()
            stateFlow.value = ScheduleLibraryState(agents = current.agents, fleet = true)
        }
        loadData()
    }

    /** In fleet mode, narrows the rows to [agent]'s; null shows every agent's again. */
    fun filterAgent(agent: AgentId?) {
        stateFlow.update { if (it.fleet) it.copy(agentFilter = agent?.value) else it }
    }

    private suspend fun loadFleet(revision: ScheduleRevision) {
        stateFlow.update { it.copy(isLoading = true, errorMessage = null) }
        val (agents, rosterError) = operations.loadAgentRoster { stateFlow.value.agents }
        if (targetRevision.value != revision.value) return
        val read = readFleet(agents.map { it.id }, native = !nativeScheduleRouteUnavailable)
        if (targetRevision.value != revision.value) return
        if (cronSource != null && read.nativeRouteUnserved()) nativeScheduleRouteUnavailable = true
        val published = stateFlow.value.fleetTarget(agents).withFleetRead(read, errorMessageMapper)
        stateFlow.value = published.copy(
            errorMessage = published.errorMessage ?: rosterError?.let { errorMessageMapper(it, "Failed to load agents") },
        )
        loadFleetRunHistory(revision)
    }

    /** Every agent's native schedules (null where the route is not served) and cron tasks, a few agents at a time. */
    private suspend fun readFleet(agents: List<AgentId>, native: Boolean): AgentFleetRead<AgentId, AgentScheduleRead> =
        AgentFleetReads.read(agents) { agent ->
            val schedules = if (native) nativeSchedulesOrNull(agent) else null
            schedules to cronSource?.listCrons(agent)
        }

    private suspend fun nativeSchedulesOrNull(agent: AgentId): List<ScheduledMessage>? =
        try {
            operations.loadSchedulesForAgent(agent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (scheduleAdminUnavailableMatcher(t)) null else throw t
        }

    /** Each agent's recorded runs for its own rows, merged; agents whose history fails keep none. */
    private suspend fun loadFleetRunHistory(revision: ScheduleRevision) {
        val source = recordSources?.runHistory ?: return
        val state = stateFlow.value
        val owned = state.schedules.map { it.agentId to it.id } + state.crons.mapNotNull { cron -> cron.agentId?.let { it to cron.id } }
        val idsByAgent = owned.groupBy({ AgentId(it.first) }, { it.second })
        val read = AgentFleetReads.read(idsByAgent.keys.toList()) { agent -> source(agent, idsByAgent.getValue(agent).distinct()) }
        if (targetRevision.value != revision.value) return
        val history = read.values.flatMap { it.second }.groupBy { it.scheduleId }
        stateFlow.update { if (it.fleet) it.copy(runHistory = history) else it }
    }

    fun selectAgent(agentId: String) {
        loadJob?.cancel()
        if (stateFlow.value.selectedAgentId != agentId) {
            targetRevision.incrementAndGet()
            cronMutationJob?.cancel()
        }
        val revision = ScheduleRevision(targetRevision.value)
        stateFlow.selectAgentTarget(AgentId(agentId))
        loadJob = scope.launch {
            val target = stateFlow.value
            // If a prior load already proved the native schedule route is
            // unavailable on this backend, skip the doomed native round-trip
            // and go straight to the cron-backed source.
            if (nativeScheduleRouteUnavailable) {
                publishCronFallbackOrUnavailable(target, revision)
                return@launch
            }
            try {
                val schedules = operations.loadSchedulesForAgent(AgentId(agentId))
                val crons = operations.loadControlCrons(AgentId(agentId))
                if (targetRevision.value != revision.value) return@launch
                stateFlow.value = target.withSchedules(schedules).withControlCrons(crons).copy(runHistory = emptyMap())
                loadRunHistory()
                loadLeases()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (targetRevision.value != revision.value) return@launch
                publishLoadFailure(t, target, revision) {
                    stateFlow.value = target.copy(
                        isLoading = false,
                        errorMessage = errorMessageMapper(t, "Failed to load schedules"),
                    )
                }
            }
        }
    }

    fun createSchedule(agentId: String, params: ScheduleCreateParams) {
        if (stateFlow.value.selectedAgentId != agentId) return
        val revision = ScheduleRevision(targetRevision.value)
        mutationJob?.cancel()
        mutationJob = scope.launch {
            val current = stateFlow.value
            try {
                scheduleRepository.createSchedule(agentId, params)
                val schedules = scheduleRepository.getSchedules(agentId).first()
                val crons = operations.loadControlCrons(AgentId(agentId))
                if (targetRevision.value != revision.value) return@launch
                stateFlow.value = current.copy(
                    selectedAgentId = agentId,
                    crons = crons ?: current.crons,
                    runHistory = emptyMap(),
                    leases = current.leases.takeIf { agentId == current.selectedAgentId }.orEmpty(),
                ).withSchedules(schedules)
                loadRunHistory()
                loadLeases()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (targetRevision.value != revision.value) return@launch
                stateFlow.value = operations.mutationFailure(t, current.copy(selectedAgentId = agentId), "Failed to create schedule")
            }
        }
    }

    /**
     * Creates the Schedules page's recurring schedule through the native schedule route, for the
     * request's agent or, when it names none, the selected one. No agent: nothing to create.
     */
    fun createRecurringSchedule(request: ScheduleCreateRequest) {
        val agentId = request.agentId ?: stateFlow.value.selectedAgentId ?: return
        if (stateFlow.value.fleet) createFleetSchedule(AgentId(agentId), request.toCreateParams())
        else createSchedule(agentId, request.toCreateParams())
    }

    /** In fleet mode, creates the schedule for the agent the form chose, then reads the fleet again. */
    private fun createFleetSchedule(agent: AgentId, params: ScheduleCreateParams) {
        val revision = ScheduleRevision(targetRevision.value)
        mutationJob?.cancel()
        mutationJob = scope.launch {
            try {
                scheduleRepository.createSchedule(agent.value, params)
                if (targetRevision.value == revision.value) loadFleet(revision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (targetRevision.value != revision.value) return@launch
                stateFlow.value = operations.mutationFailure(t, stateFlow.value, "Failed to create schedule")
            }
        }
    }

    /** In fleet mode, deletes [schedule] through its own agent [owner], then reads the fleet again. */
    private fun deleteFleetSchedule(owner: AgentId, schedule: ScheduleIdentity) {
        val current = stateFlow.value
        val revision = ScheduleRevision(targetRevision.value)
        mutationJob?.cancel()
        mutationJob = scope.launch {
            try {
                operations.deleteAndReload(current, owner, schedule)
                if (targetRevision.value == revision.value) loadFleet(revision)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (targetRevision.value != revision.value) return@launch
                stateFlow.value = operations.mutationFailure(t, stateFlow.value, "Failed to delete schedule")
            }
        }
    }

    /**
     * Deletes a schedule of the selected agent, or in fleet mode of its [owner] (the row's own agent;
     * a fleet delete without one does nothing). With an agent selected, a different [owner] does nothing.
     */
    fun deleteSchedule(scheduledMessageId: String, owner: AgentId? = null) {
        val identity = ScheduleIdentity(scheduledMessageId)
        when {
            stateFlow.value.fleet -> owner?.let { deleteFleetSchedule(it, identity) }
            owner == null || owner.value == stateFlow.value.selectedAgentId -> deleteSelectedSchedule(identity)
        }
    }

    private fun deleteSelectedSchedule(identity: ScheduleIdentity) {
        val revision = ScheduleRevision(targetRevision.value)
        mutationJob?.cancel()
        mutationJob = scope.launch {
            val current = stateFlow.value
            val agentId = current.selectedAgentId ?: return@launch
            try {
                val schedules = operations.deleteAndReload(current, AgentId(agentId), identity)
                val crons = operations.loadControlCrons(AgentId(agentId))
                if (targetRevision.value != revision.value) return@launch
                stateFlow.value = current.withSchedules(schedules).copy(
                    crons = crons ?: current.crons,
                    runHistory = emptyMap(),
                    leases = current.leases,
                )
                loadRunHistory()
                loadLeases()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (targetRevision.value != revision.value) return@launch
                stateFlow.value = operations.mutationFailure(t, current, "Failed to delete schedule")
            }
        }
    }

    override fun close() {
        loadJob?.cancel()
        mutationJob?.cancel()
        cronMutationJob?.cancel()
    }

    /** Pause or resume once, retaining the current rows until the host's refreshed list arrives. */
    fun toggleCronPause(task: CronTask, resume: Boolean) {
        val taskId = task.id
        if (cronRepository == null) return
        val current = stateFlow.value
        val agent = current.selectedAgentId ?: task.agentId.takeIf { current.fleet } ?: return
        if (cronMutationJob?.isActive == true) return
        if (!current.canControlCron(task, resume)) return
        val revision = ScheduleRevision(targetRevision.value)
        stateFlow.update { it.copy(pendingCronIds = setOf(taskId), errorMessage = null) }
        cronMutationJob = scope.launch {
            performCronPauseChange(task, resume, revision, AgentId(agent))
        }
    }

    private suspend fun performCronPauseChange(task: CronTask, resume: Boolean, revision: ScheduleRevision, agent: AgentId) {
        val repository = checkNotNull(cronRepository)
        try {
            if (resume) repository.resumeSchedule(agent.value, task.id).getOrThrow()
            else repository.pauseSchedule(agent.value, task.id).getOrThrow()
            val crons = cronSource?.listCrons(agent).orEmpty()
            if (targetRevision.value == revision.value) stateFlow.update { it.withAgentCrons(agent, crons) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (targetRevision.value == revision.value) stateFlow.update {
                it.copy(errorMessage = errorMessageMapper(t, "Failed to change schedule state"))
            }
        } finally {
            if (targetRevision.value == revision.value) stateFlow.update { it.copy(pendingCronIds = emptySet()) }
        }
    }

    /**
     * Native schedule route is unavailable. Try the cron-backed fallback
     * (the backend's `/v1/crons` route, served by self-hosted / admin-shim
     * servers). If crons load — even an empty list — publish a
     * cron-backed state (a real "0 schedules", NOT the unavailable wall).
     * Only if the cron route is ALSO unavailable do we fall back to the
     * unavailable state. Parity with the desktop schedules surface.
     */
    private suspend fun publishCronFallbackOrUnavailable(target: ScheduleLibraryState, revision: ScheduleRevision) {
        val crons = try {
            cronSource?.listCrons(target.selectedAgentId?.let(::AgentId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            if (cronRepository == null) null else {
                if (targetRevision.value == revision.value) stateFlow.value = target.copy(
                    isLoading = false, errorMessage = errorMessageMapper(t, "Failed to load schedules"),
                )
                return
            }
        }
        if (targetRevision.value != revision.value) return
        stateFlow.value = if (crons != null) {
            target.withSchedules(emptyList()).copy(crons = crons, cronMode = true, runHistory = emptyMap())
        } else {
            ScheduleLibraryState(
                agents = target.agents,
                selectedAgentId = target.selectedAgentId,
                leases = target.leases,
            ).adminUnavailable()
        }
        loadRunHistory()
        loadLeases()
    }

    private suspend fun loadRunHistory() {
        val source = recordSources?.runHistory ?: return
        val state = stateFlow.value
        val agent = state.selectedAgentId ?: return
        val ids = (state.schedules.map { it.id } + state.crons.map { it.id }).distinct()
        try {
            val records = source(AgentId(agent), ids)
            stateFlow.update {
                if (it.selectedAgentId == agent) it.copy(runHistory = records.groupBy { record -> record.scheduleId }) else it
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            stateFlow.update { it.copy(errorMessage = errorMessageMapper(t, "Failed to load run history")) }
        }
    }

    private suspend fun loadLeases() {
        val source = recordSources?.leases ?: return
        val agent = stateFlow.value.selectedAgentId ?: return
        try {
            val leases = source(AgentId(agent))
            stateFlow.update { if (it.selectedAgentId == agent) it.copy(leases = leases) else it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            stateFlow.update {
                if (it.selectedAgentId == agent) {
                    val message = errorMessageMapper(t, "Failed to load leases")
                    it.copy(errorMessage = listOfNotNull(it.errorMessage, message).joinToString("\n"))
                } else {
                    it
                }
            }
        }
    }

}

const val SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE = "Schedule admin isn't available on this Letta server."

/** A loaded state: these schedules, admin available, no error. */
private fun ScheduleLibraryState.withSchedules(schedules: List<ScheduledMessage>): ScheduleLibraryState =
    copy(
        schedules = schedules,
        scheduleAdminAvailable = true,
        scheduleAdminMessage = null,
        isLoading = false,
        errorMessage = null,
    )

/** The native schedule route is unavailable (and no cron fallback answered). */
internal fun ScheduleLibraryState.adminUnavailable(): ScheduleLibraryState =
    copy(
        schedules = emptyList(),
        scheduleAdminAvailable = false,
        scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
        isLoading = false,
        errorMessage = null,
    )

/**
 * Cron-backed schedule source. Implemented on each platform over the
 * backend's `/v1/crons` route (the route self-hosted / admin-shim servers
 * serve when the Letta-native `/v1/agents/{id}/schedule` admin route 404s).
 * Returns the cron tasks, optionally scoped to [agentId]. Used by
 * [ScheduleLibraryController] as a fallback so mobile reaches parity with
 * the desktop schedules surface.
 */
fun interface CronScheduleSource {
    suspend fun listCrons(agentId: AgentId?): List<CronTask>

    /** Optional control repository belonging to the same host as this list source. */
    val repository: ICronRepository? get() = null
}


private fun ScheduleLibraryState.withControlCrons(crons: List<CronTask>?): ScheduleLibraryState =
    copy(crons = crons ?: this.crons)

private fun MutableStateFlow<ScheduleLibraryState>.selectAgentTarget(agent: AgentId) {
    val agentId = agent.value
    update { current ->
        if (current.selectedAgentId == agentId) current else current.copy(
            selectedAgentId = agentId,
            fleet = false,
            agentFilter = null,
            schedules = emptyList(),
            crons = emptyList(),
            pendingCronIds = emptySet(),
            runHistory = emptyMap(),
            leases = emptyList(),
            isLoading = true,
            errorMessage = null,
        )
    }
}

private fun ScheduleLibraryState.targetForAgents(agents: List<AgentSummary>): ScheduleLibraryState {
    val current = this
    val selected = current.selectedAgentId
    return ScheduleLibraryState(
        agents = agents,
        selectedAgentId = selected,
        leases = current.leases.takeIf { selected == current.selectedAgentId }.orEmpty(),
    )
}
