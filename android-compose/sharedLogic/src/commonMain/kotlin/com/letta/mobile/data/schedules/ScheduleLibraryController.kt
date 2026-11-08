package com.letta.mobile.data.schedules

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.schedules.leases.Lease
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
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
) {
    /** Crons scoped to the selected agent (cron tasks may carry no agent id). */
    val cronsForSelectedAgent: List<CronTask>
        get() = selectedAgentId?.let { agentId ->
            crons.filter { it.agentId == null || it.agentId == agentId }
        } ?: crons

    val displayItems: List<ScheduleLibraryItem>
        get() = schedules.map { it.toScheduleLibraryItem() }

    val emptyMessage: String
        get() = when {
            cronMode -> "No schedules for this agent."
            !scheduleAdminAvailable -> scheduleAdminMessage ?: SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE
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
    private val stateFlow = MutableStateFlow(ScheduleLibraryState())
    val state: StateFlow<ScheduleLibraryState> = stateFlow

    private var loadJob: Job? = null
    private var mutationJob: Job? = null
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
        val revision = targetRevision.value
        loadJob = scope.launch {
            stateFlow.update { it.copy(isLoading = true, errorMessage = null) }
            var rosterError: Throwable? = null
            val agents = try {
                agentRepository.listAgentSummaries()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                rosterError = t
                stateFlow.value.agents
            }
            if (targetRevision.value != revision) return@launch
            val target = targetForAgents(agents)
            try {
                if (nativeScheduleRouteUnavailable) {
                    publishCronFallbackOrUnavailable(target, revision)
                    return@launch
                }
                val schedules = target.selectedAgentId?.let { loadSchedulesForAgent(it) }.orEmpty()
                if (targetRevision.value != revision) return@launch
                stateFlow.value = target.withSchedules(schedules).copy(
                    errorMessage = rosterError?.let { errorMessageMapper(it, "Failed to load agents") },
                )
                loadRunHistory()
                loadLeases()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (targetRevision.value != revision) return@launch
                if (scheduleAdminUnavailableMatcher(t)) {
                    latchNativeUnavailableIfCronBacked()
                    publishCronFallbackOrUnavailable(target, revision)
                } else {
                    stateFlow.update { current ->
                        current.copy(
                            agents = agents,
                            isLoading = false,
                            errorMessage = errorMessageMapper(t, "Failed to load schedules"),
                        )
                    }
                    loadLeases()
                }
            }
        }
    }

    fun selectAgent(agentId: String) {
        loadJob?.cancel()
        if (stateFlow.value.selectedAgentId != agentId) targetRevision.incrementAndGet()
        val revision = targetRevision.value
        stateFlow.update { current ->
            if (current.selectedAgentId == agentId) current else current.copy(
                selectedAgentId = agentId,
                schedules = emptyList(),
                crons = emptyList(),
                runHistory = emptyMap(),
                leases = emptyList(),
                isLoading = true,
                errorMessage = null,
            )
        }
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
                val schedules = loadSchedulesForAgent(agentId)
                if (targetRevision.value != revision) return@launch
                stateFlow.value = target.withSchedules(schedules).copy(runHistory = emptyMap())
                loadRunHistory()
                loadLeases()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (targetRevision.value != revision) return@launch
                if (scheduleAdminUnavailableMatcher(t)) {
                    latchNativeUnavailableIfCronBacked()
                    publishCronFallbackOrUnavailable(target, revision)
                } else {
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
        val revision = targetRevision.value
        mutationJob?.cancel()
        mutationJob = scope.launch {
            val current = stateFlow.value
            try {
                scheduleRepository.createSchedule(agentId, params)
                val schedules = scheduleRepository.getSchedules(agentId).first()
                if (targetRevision.value != revision) return@launch
                stateFlow.value = current.copy(
                    selectedAgentId = agentId,
                    runHistory = emptyMap(),
                    leases = current.leases.takeIf { agentId == current.selectedAgentId }.orEmpty(),
                ).withSchedules(schedules)
                loadRunHistory()
                loadLeases()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (targetRevision.value != revision) return@launch
                if (scheduleAdminUnavailableMatcher(t)) {
                    stateFlow.value = current.copy(selectedAgentId = agentId).adminUnavailable()
                } else {
                    stateFlow.value = current.copy(
                        isLoading = false,
                        errorMessage = errorMessageMapper(t, "Failed to create schedule"),
                    )
                }
            }
        }
    }

    /**
     * Creates the Schedules page's recurring schedule through the native schedule route, for the
     * request's agent or, when it names none, the selected one. No agent: nothing to create.
     */
    fun createRecurringSchedule(request: ScheduleCreateRequest) {
        val agentId = request.agentId ?: stateFlow.value.selectedAgentId ?: return
        createSchedule(agentId, request.toCreateParams())
    }

    fun deleteSchedule(scheduledMessageId: String) {
        val revision = targetRevision.value
        mutationJob?.cancel()
        mutationJob = scope.launch {
            val current = stateFlow.value
            val agentId = current.selectedAgentId ?: return@launch
            try {
                scheduleRepository.deleteSchedule(agentId, scheduledMessageId)
                val schedules = scheduleRepository.getSchedules(agentId).first()
                if (targetRevision.value != revision) return@launch
                stateFlow.value = current.withSchedules(schedules).copy(
                    runHistory = emptyMap(),
                    leases = current.leases,
                )
                loadRunHistory()
                loadLeases()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (targetRevision.value != revision) return@launch
                if (scheduleAdminUnavailableMatcher(t)) {
                    stateFlow.value = current.adminUnavailable()
                } else {
                    stateFlow.value = current.copy(
                        isLoading = false,
                        errorMessage = errorMessageMapper(t, "Failed to delete schedule"),
                    )
                }
            }
        }
    }

    override fun close() {
        loadJob?.cancel()
        mutationJob?.cancel()
    }

    private fun targetForAgents(agents: List<AgentSummary>): ScheduleLibraryState {
        val current = stateFlow.value
        val selected = current.selectedAgentId
        return ScheduleLibraryState(
            agents = agents,
            selectedAgentId = selected,
            leases = current.leases.takeIf { selected == current.selectedAgentId }.orEmpty(),
        )
    }

    private suspend fun loadSchedulesForAgent(agentId: String): List<ScheduledMessage> {
        scheduleRepository.refreshSchedules(agentId)
        return scheduleRepository.getSchedules(agentId).first()
    }

    /**
     * Latch the "native schedule route is unavailable" verdict so subsequent
     * loads skip the doomed native round-trip. Only latched when a [cronSource]
     * is wired (i.e. the backend is genuinely cron-backed); without a cron
     * source the controller keeps probing native and lands on the same
     * unavailable state either way, so latching there would change nothing.
     */
    private fun latchNativeUnavailableIfCronBacked() {
        if (cronSource != null) {
            nativeScheduleRouteUnavailable = true
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
    private suspend fun publishCronFallbackOrUnavailable(target: ScheduleLibraryState, revision: Long) {
        val crons = cronSource?.let { source -> runCatching { source.listCrons(target.selectedAgentId) }.getOrNull() }
        if (targetRevision.value != revision) return
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
private fun ScheduleLibraryState.adminUnavailable(): ScheduleLibraryState =
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
    suspend fun listCrons(agentId: String?): List<CronTask>
}
