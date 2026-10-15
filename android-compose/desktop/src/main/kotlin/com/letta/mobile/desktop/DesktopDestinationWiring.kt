package com.letta.mobile.desktop

import com.letta.mobile.data.schedules.ScheduleLibraryController
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.data.skills.SkillsCatalogController
import com.letta.mobile.ui.shell.pages.schedules.SchedulesPageActions

/** Dependencies for wiring the Schedules destination actions. */
internal data class ScheduleWiringDeps(
    val schedules: ScheduleLibraryController,
    val cronPanel: DesktopCronPanelState,
    val scheduleLibraryState: ScheduleLibraryState,
    val selectedAgentId: String?,
)

/**
 * The shared Schedules page's actions on desktop: the page says which list a deleted row came
 * from (cron panel or native schedule library), and creates prefer the HTTP cron API when it
 * exists (Iroh uses the native schedule.create path via the library controller).
 */
internal fun destinationScheduleActions(deps: ScheduleWiringDeps): SchedulesPageActions =
    SchedulesPageActions(
        onRefresh = deps.schedules::loadData,
        onSelectAgent = deps.schedules::selectAgent,
        // The controller deletes under its selected agent: drop a delete for any other agent's row.
        onDeleteSchedule = { ref ->
            if (ref.agentId == deps.scheduleLibraryState.selectedAgentId) deps.schedules.deleteSchedule(ref.scheduleId)
        },
        onDeleteCron = { id -> deps.cronPanel.delete(DesktopCronTaskId(id.value)) },
        onCreate = { request ->
            val targetAgent = request.agentId ?: deps.selectedAgentId
            when {
                // No agent focused: the page hides "New schedule" then.
                targetAgent == null -> Unit
                deps.cronPanel.available -> deps.cronPanel.create(
                    CronDraft(
                        agentId = DesktopAgentId(targetAgent),
                        name = request.name,
                        prompt = request.prompt,
                        cron = request.cron,
                        recurring = request.recurring,
                        timezone = request.timezone,
                    ),
                )
                else -> deps.schedules.createRecurringSchedule(request.copy(agentId = targetAgent))
            }
        },
        onTogglePause = { id, isCurrentlyPaused ->
            if (isCurrentlyPaused) {
                deps.cronPanel.resume(DesktopCronTaskId(id))
            } else {
                deps.cronPanel.pause(DesktopCronTaskId(id))
            }
        },
    )

/** The shared Skills page's skill actions over the shared catalog controller, for the focused agent. */
internal fun destinationSkillsActions(
    skills: SkillsCatalogController,
    selectedAgentId: String?,
): DestinationSkillsActions = DestinationSkillsActions(
    onRefresh = { skills.reload(selectedAgentId) },
    onInstall = skills::install,
    onUninstall = skills::uninstall,
)
