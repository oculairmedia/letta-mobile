package com.letta.mobile.desktop

import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryController
import com.letta.mobile.desktop.schedules.DesktopScheduleLibraryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Dependencies for wiring the Schedules destination actions. */
internal data class ScheduleWiringDeps(
    val schedules: DesktopScheduleLibraryController,
    val cronPanel: DesktopCronPanelState,
    val scheduleLibraryState: DesktopScheduleLibraryState,
    val selectedAgentId: String?,
)

/**
 * Schedules actions: deletes route to whichever backend owns the schedule id,
 * and creates prefer the HTTP cron API when it exists (iroh uses the native
 * schedule.create path via the library controller).
 */
internal fun destinationScheduleActions(deps: ScheduleWiringDeps): DestinationScheduleActions =
    DestinationScheduleActions(
        onRefresh = deps.schedules::reload,
        onAgentSelected = deps.schedules::selectAgent,
        onDeleteCron = { id ->
            if (deps.scheduleLibraryState.schedules.any { it.id == id }) {
                deps.schedules.deleteSchedule(id)
            } else {
                deps.cronPanel.delete(DesktopCronTaskId(id))
            }
        },
        onCreateCron = { filteredAgentId, name, prompt, cron, recurring, tz ->
            val targetAgent = filteredAgentId
                ?: deps.scheduleLibraryState.selectedAgentId
                ?: deps.selectedAgentId
            if (targetAgent == null) {
                // No agent focused — create UI should already be disabled.
            } else if (deps.cronPanel.available) {
                deps.cronPanel.create(
                    CronDraft(
                        agentId = DesktopAgentId(targetAgent),
                        name = name,
                        prompt = prompt,
                        cron = cron,
                        recurring = recurring,
                        timezone = tz,
                    ),
                )
            } else {
                deps.schedules.createRecurringSchedule(
                    agentId = targetAgent,
                    name = name,
                    prompt = prompt,
                    cronExpression = cron,
                )
            }
        },
    )

internal fun destinationSkillsActions(
    skillsPanel: DesktopSkillsPanelState,
    chatScope: CoroutineScope,
    selectedAgentId: String?,
): DestinationSkillsActions = DestinationSkillsActions(
    onRefresh = {
        chatScope.launch {
            skillsPanel.reload(selectedAgentId?.let(::DesktopAgentId))
        }
    },
    onInstall = { name ->
        skillsPanel.install(
            selectedAgentId?.let(::DesktopAgentId),
            DesktopSkillName(name),
        )
    },
    onUninstall = { name ->
        skillsPanel.uninstall(
            selectedAgentId?.let(::DesktopAgentId),
            DesktopSkillName(name),
        )
    },
)
