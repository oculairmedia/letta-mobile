package com.letta.mobile.data.schedules

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IScheduleRepository
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
) {
    val selectedAgent: AgentSummary?
        get() = agents.firstOrNull { it.id.value == selectedAgentId }

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
            ScheduleTiming.OneTime(nextScheduledTime ?: schedule.scheduledAt?.toString().orEmpty())
        },
    )

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
    /**
     * Optional agent to pre-select when the surface is opened from a context
     * that already knows the "current" agent (e.g. the chat drawer's
     * Schedules entry point). When non-null and present in the loaded agents
     * list, it is preferred as the initial selection; otherwise selection
     * falls back to the existing [resolveSelection] behaviour (first agent).
     * Defaults to null so every other entry point (admin/conversations
     * menus) keeps the original "no pre-selected agent" behaviour. Mirrors
     * the Memory surface, which carries the agent id through MemoryRoute.
     */
    private val initialAgentId: String? = null,
) : AutoCloseable {
    private val stateFlow = MutableStateFlow(ScheduleLibraryState())
    val state: StateFlow<ScheduleLibraryState> = stateFlow

    private var loadJob: Job? = null
    private var mutationJob: Job? = null

    fun start() {
        if (stateFlow.value.agents.isEmpty() && stateFlow.value.schedules.isEmpty()) {
            // Seed the requested agent BEFORE loading so loadData()'s
            // resolveSelection prefers it (when it exists in the agents list)
            // and otherwise falls back gracefully. A null initialAgentId
            // leaves selectedAgentId null, preserving the legacy default.
            if (initialAgentId != null && stateFlow.value.selectedAgentId == null) {
                stateFlow.update { it.copy(selectedAgentId = initialAgentId) }
            }
            loadData()
        }
    }

    fun loadData() {
        loadJob?.cancel()
        loadJob = scope.launch {
            stateFlow.update { it.copy(isLoading = true, errorMessage = null) }
            // Picker only needs id+name+description; pull the slim agents
            // projection (admin-shim `?slim=true`) instead of the full
            // ~621KB refreshAgents() payload. Keeps other screens' shared
            // full-agent cache untouched. Captured outside the try so the
            // schedule-unavailable fallback can reuse the agents it loaded.
            var loadedAgents: List<AgentSummary> = stateFlow.value.agents
            try {
                val agents = agentRepository.listAgentSummaries()
                loadedAgents = agents
                val selectedAgentId = agents.resolveSelection(stateFlow.value.selectedAgentId)
                val schedules = selectedAgentId?.let { loadSchedulesForAgent(it) }.orEmpty()
                stateFlow.value = ScheduleLibraryState(
                    agents = agents,
                    selectedAgentId = selectedAgentId,
                    schedules = schedules,
                    scheduleAdminAvailable = true,
                    scheduleAdminMessage = null,
                    isLoading = false,
                    errorMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (scheduleAdminUnavailableMatcher(t)) {
                    val agents = loadedAgents
                    val selectedAgentId = agents.resolveSelection(stateFlow.value.selectedAgentId)
                    publishCronFallbackOrUnavailable(agents, selectedAgentId)
                } else {
                    stateFlow.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = errorMessageMapper(t, "Failed to load schedules"),
                        )
                    }
                }
            }
        }
    }

    fun selectAgent(agentId: String) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val current = stateFlow.value
            try {
                val schedules = loadSchedulesForAgent(agentId)
                stateFlow.value = current.copy(
                    selectedAgentId = agentId,
                    schedules = schedules,
                    scheduleAdminAvailable = true,
                    scheduleAdminMessage = null,
                    isLoading = false,
                    errorMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (scheduleAdminUnavailableMatcher(t)) {
                    publishCronFallbackOrUnavailable(
                        agents = current.agents,
                        selectedAgentId = agentId,
                        base = current.copy(selectedAgentId = agentId),
                    )
                } else {
                    stateFlow.value = current.copy(
                        isLoading = false,
                        errorMessage = errorMessageMapper(t, "Failed to load schedules"),
                    )
                }
            }
        }
    }

    fun createSchedule(agentId: String, params: ScheduleCreateParams) {
        mutationJob?.cancel()
        mutationJob = scope.launch {
            val current = stateFlow.value
            try {
                scheduleRepository.createSchedule(agentId, params)
                val schedules = scheduleRepository.getSchedules(agentId).first()
                stateFlow.value = current.copy(
                    selectedAgentId = agentId,
                    schedules = schedules,
                    scheduleAdminAvailable = true,
                    scheduleAdminMessage = null,
                    isLoading = false,
                    errorMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (scheduleAdminUnavailableMatcher(t)) {
                    stateFlow.value = current.copy(
                        selectedAgentId = agentId,
                        schedules = emptyList(),
                        scheduleAdminAvailable = false,
                        scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
                        isLoading = false,
                        errorMessage = null,
                    )
                } else {
                    stateFlow.value = current.copy(
                        isLoading = false,
                        errorMessage = errorMessageMapper(t, "Failed to create schedule"),
                    )
                }
            }
        }
    }

    fun deleteSchedule(scheduledMessageId: String) {
        mutationJob?.cancel()
        mutationJob = scope.launch {
            val current = stateFlow.value
            val agentId = current.selectedAgentId ?: return@launch
            try {
                scheduleRepository.deleteSchedule(agentId, scheduledMessageId)
                val schedules = scheduleRepository.getSchedules(agentId).first()
                stateFlow.value = current.copy(
                    schedules = schedules,
                    scheduleAdminAvailable = true,
                    scheduleAdminMessage = null,
                    isLoading = false,
                    errorMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (scheduleAdminUnavailableMatcher(t)) {
                    stateFlow.value = current.copy(
                        schedules = emptyList(),
                        scheduleAdminAvailable = false,
                        scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
                        isLoading = false,
                        errorMessage = null,
                    )
                } else {
                    stateFlow.value = current.copy(
                        isLoading = false,
                        errorMessage = errorMessageMapper(t, "Failed to delete schedule"),
                    )
                }
            }
        }
    }

    fun showError(message: String) {
        stateFlow.update {
            it.copy(
                isLoading = false,
                errorMessage = message,
            )
        }
    }

    override fun close() {
        loadJob?.cancel()
        mutationJob?.cancel()
    }

    private suspend fun loadSchedulesForAgent(agentId: String): List<ScheduledMessage> {
        scheduleRepository.refreshSchedules(agentId)
        return scheduleRepository.getSchedules(agentId).first()
    }

    /**
     * Native schedule route is unavailable. Try the cron-backed fallback
     * (the backend's `/v1/crons` route, served by self-hosted / admin-shim
     * servers). If crons load — even an empty list — publish a
     * cron-backed state (a real "0 schedules", NOT the unavailable wall).
     * Only if the cron route is ALSO unavailable do we fall back to the
     * unavailable state. Parity with the desktop schedules surface.
     */
    private suspend fun publishCronFallbackOrUnavailable(
        agents: List<AgentSummary>,
        selectedAgentId: String?,
        base: ScheduleLibraryState? = null,
    ) {
        val source = cronSource
        if (source != null) {
            val crons = runCatching { source.listCrons(selectedAgentId) }.getOrNull()
            if (crons != null) {
                val resolved = base ?: ScheduleLibraryState()
                stateFlow.value = resolved.copy(
                    agents = agents,
                    selectedAgentId = selectedAgentId,
                    schedules = emptyList(),
                    crons = crons,
                    cronMode = true,
                    scheduleAdminAvailable = true,
                    scheduleAdminMessage = null,
                    isLoading = false,
                    errorMessage = null,
                )
                return
            }
        }
        publishUnavailableState(agents, selectedAgentId)
    }

    private fun publishUnavailableState(
        fallbackAgents: List<AgentSummary>,
        requestedAgentId: String?,
    ) {
        val selectedAgentId = fallbackAgents.resolveSelection(requestedAgentId)
        stateFlow.value = ScheduleLibraryState(
            agents = fallbackAgents,
            selectedAgentId = selectedAgentId,
            schedules = emptyList(),
            scheduleAdminAvailable = false,
            scheduleAdminMessage = SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE,
            isLoading = false,
            errorMessage = null,
        )
    }

    private fun List<AgentSummary>.resolveSelection(requestedAgentId: String?): String? =
        if (requestedAgentId != null && any { it.id.value == requestedAgentId }) {
            requestedAgentId
        } else {
            firstOrNull()?.id?.value
        }
}

const val SCHEDULE_ADMIN_UNAVAILABLE_MESSAGE = "Schedule admin isn't available on this Letta server."

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
