package com.letta.mobile.ui.shell.pages

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.schedules.ScheduleCreateRequest
import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.data.schedules.fleet.ScheduleFleetController
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.pages.schedules.CronTaskId
import com.letta.mobile.ui.shell.pages.schedules.NativeScheduleRef
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageState
import com.letta.mobile.ui.shell.shellSchedulesPage

/**
 * The shared Schedules page. With an agent focused it shows that agent's library; with none it
 * shows the fleet's (every agent's schedules, the agent chips narrowing them), so no earlier
 * agent's rows linger. A schedule can be made where the session serves them and there is an agent
 * to make it for (the focused one, or one chosen in the form).
 */
internal fun schedulesPage(
    graph: SessionRepositoryGraph,
    controllers: ShellLibraryControllers,
    host: ShellPageHost,
) = ShellPageContent<ShellDestination.Schedules> { destination, modifier ->
    val library by controllers.schedules.state.collectAsState()
    val fleet by controllers.scheduleFleet.state.collectAsState()
    val focused = host.navigator.state.currentFocus.agentId?.value
    val shown = if (focused == null) fleet else library
    val state = SchedulesPageState(
        library = shown,
        focusedAgentId = focused,
        canCreate = graph.capabilities.supports(SessionRoute.SCHEDULES) && (focused != null || shown.agents.isNotEmpty()),
    )
    val actions = shellScheduleActions(controllers.schedules, host.navigator.state, controllers.scheduleFleet)
    shellSchedulesPage(state, actions).Content(destination, modifier)
}

/**
 * The Schedules page's actions. With an agent focused, a row or form can mutate only while its
 * agent still matches both the workspace focus and the library's current query target. With none
 * focused they go to [fleet], each row through its own agent.
 */
internal fun shellScheduleActions(
    schedules: ScheduleLibraryController,
    workspace: ShellWorkspaceState,
    fleet: ScheduleFleetController? = null,
): SchedulesPageActions {
    val targets = ScheduleTargets(schedules, workspace, fleet)
    return SchedulesPageActions(
        onRefresh = targets::refresh,
        onFilterAgent = { id -> targets.unfocusedFleet?.filterAgent(id?.let(::AgentId)) },
        onDeleteSchedule = targets::deleteNative,
        onDeleteCron = targets::deleteCron,
        onTogglePause = targets::togglePause,
        onCreate = targets::create,
    )
}

/** Where a Schedules action goes: the fleet while no agent is focused, else the focused agent's library. */
private class ScheduleTargets(
    val schedules: ScheduleLibraryController,
    val workspace: ShellWorkspaceState,
    val fleet: ScheduleFleetController?,
) {
    /** The fleet, while no agent is focused. */
    val unfocusedFleet: ScheduleFleetController? get() = fleet?.takeIf { workspace.currentFocus.agentId == null }

    /** The focused agent, while the library still queries it. */
    private val focusedAgent: String?
        get() = workspace.currentFocus.agentId?.value?.takeIf { schedules.state.value.selectedAgentId == it }

    fun refresh() {
        val fleet = unfocusedFleet
        if (fleet != null) fleet.refresh()
        else if (workspace.currentFocus.agentId?.value == schedules.state.value.selectedAgentId) schedules.loadData()
    }

    fun deleteNative(ref: NativeScheduleRef) {
        val fleet = unfocusedFleet
        if (fleet != null) {
            fleet.state.value.schedules.firstOrNull { it.id == ref.scheduleId && it.agentId == ref.agentId }?.let(fleet::deleteNative)
        } else if (focusedAgent == ref.agentId) {
            schedules.deleteSchedule(ref.scheduleId)
        }
    }

    fun deleteCron(id: CronTaskId) {
        val fleet = unfocusedFleet
        if (fleet != null) fleet.state.value.crons.firstOrNull { it.id == id.value }?.let(fleet::deleteCron)
        else if (focusedAgent != null) schedules.deleteSchedule(id.value)
    }

    fun togglePause(id: String, resume: Boolean) {
        val fleet = unfocusedFleet
        if (fleet != null) {
            fleet.state.value.crons.firstOrNull { it.id == id }?.let { fleet.toggleCronPause(it, resume) }
        } else if (focusedAgent != null) {
            schedules.state.value.crons.firstOrNull { it.id == id }?.let { schedules.toggleCronPause(it, resume) }
        }
    }

    fun create(request: ScheduleCreateRequest) {
        val fleet = unfocusedFleet
        if (fleet != null) return fleet.createSchedule(request)
        val agent = focusedAgent ?: return
        if (request.agentId == null || request.agentId == agent) schedules.createRecurringSchedule(request.copy(agentId = agent))
    }
}
