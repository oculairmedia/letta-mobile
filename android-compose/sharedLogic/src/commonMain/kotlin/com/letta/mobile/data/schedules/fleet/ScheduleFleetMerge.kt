package com.letta.mobile.data.schedules.fleet

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentSummary
import com.letta.mobile.data.schedules.CronTask
import com.letta.mobile.data.schedules.ScheduleLibraryState
import com.letta.mobile.data.schedules.adminUnavailable
import com.letta.mobile.util.AgentFleetRead

/** Publishes a fleet read onto this target: rows in roster order, one agent's failure tolerated. */
internal fun ScheduleLibraryState.withFleetRead(
    read: AgentFleetRead<AgentId, AgentScheduleRead>,
    errorMessage: (Throwable, String) -> String,
): ScheduleLibraryState {
    if (read.allFailed) {
        val failure = checkNotNull(read.firstFailure)
        return copy(isLoading = false, errorMessage = errorMessage(failure, FLEET_LOAD_FAILED))
    }
    val reads = read.values.map { it.second }
    val nativeServed = reads.any { it.native != null }
    val cronServed = reads.any { it.crons != null }
    val anyServed = nativeServed || cronServed
    if (reads.isNotEmpty() && !anyServed) return adminUnavailable().copy(crons = emptyList(), cronMode = false)
    return copy(
        schedules = reads.flatMap { it.native.orEmpty() }.distinctBy { it.id },
        crons = reads.flatMap { it.crons.orEmpty() }.distinctBy { it.id },
        cronMode = !nativeServed && cronServed,
        scheduleAdminAvailable = true,
        scheduleAdminMessage = null,
        isLoading = false,
        errorMessage = fleetPartialFailure(read.failures.size + reads.count { it.cronFailure != null }),
    )
}

/** Every read answered and none on the native route: later fleet reads skip it. */
internal fun AgentFleetRead<AgentId, AgentScheduleRead>.nativeRouteUnserved(): Boolean =
    failures.isEmpty() && values.isNotEmpty() && values.none { it.second.native != null }

/** These crons with [agent]'s replaced by [fresh], in [roster] order. */
internal fun List<CronTask>.replacingAgentCrons(
    agent: AgentId,
    fresh: List<CronTask>,
    roster: List<AgentSummary>,
): List<CronTask> {
    val order = roster.mapIndexed { index, summary -> summary.id.value to index }.toMap()
    return (filterNot { it.agentId == agent.value } + fresh)
        .distinctBy { it.id }
        .sortedBy { task -> task.agentId?.let(order::get) ?: Int.MAX_VALUE }
}

/**
 * The fleet scope over [agents]: every agent's schedules, or only the filtered agent's when it is still
 * listed. The rows on screen stay until a read replaces them, so a failed read keeps them.
 */
internal fun ScheduleLibraryState.fleetTarget(agents: List<AgentSummary>): ScheduleLibraryState =
    copy(
        agents = agents,
        selectedAgentId = null,
        fleet = true,
        agentFilter = agentFilter?.takeIf { filter -> agents.isEmpty() || agents.any { it.id.value == filter } },
    )

private fun fleetPartialFailure(failed: Int): String? = when (failed) {
    0 -> null
    1 -> "Could not load schedules for 1 agent."
    else -> "Could not load schedules for $failed agents."
}

private const val FLEET_LOAD_FAILED = "Failed to load schedules"
