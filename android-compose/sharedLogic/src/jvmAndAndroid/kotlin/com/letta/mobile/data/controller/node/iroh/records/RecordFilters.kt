package com.letta.mobile.data.controller.node.iroh.records

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class CronRunFilter(
    private val taskId: String,
    private val agent: String?,
    private val authorizedAgentIds: Set<String>?,
    private val wrapperTaskId: (String) -> String,
    private val isAgentInScope: (Set<String>?, String) -> Boolean,
) {
    fun includesUpstream(row: JsonObject): Boolean {
        val owner = (row["agentId"] as? JsonPrimitive)?.contentOrNull
        return (authorizedAgentIds == null || owner in authorizedAgentIds) && matchesAgent(owner)
    }

    fun includesStored(row: StoredRunRecord): Boolean =
        (row.input.scheduleId ?: wrapperTaskId(row.input.agentId)) == taskId &&
            isAgentInScope(authorizedAgentIds, row.input.agentId) && matchesAgent(row.input.agentId)

    private fun matchesAgent(owner: String?): Boolean = agent == null || owner == agent
}

internal data class UpstreamPagePosition(val total: Int, val next: Int)

internal class LeaseFilter(
    private val agent: String?,
    private val state: RecordedLeaseState?,
    private val from: Long?,
    private val to: Long?,
    private val isAgentInScope: (String) -> Boolean,
) {
    fun includes(row: StoredLeaseRecord): Boolean =
        isAgentInScope(row.input.owner) && matchesAgent(row) && matchesStateAndTime(row)

    private fun matchesAgent(row: StoredLeaseRecord): Boolean = agent == null || row.input.owner == agent

    private fun matchesStateAndTime(row: StoredLeaseRecord): Boolean =
        (state == null || row.input.state == state) && overlaps(row)

    private fun overlaps(row: StoredLeaseRecord): Boolean =
        (from == null || row.input.dueAtMs >= from) && startsBeforeEnd(row)

    private fun startsBeforeEnd(row: StoredLeaseRecord): Boolean = to == null || row.input.startedAtMs <= to
}
