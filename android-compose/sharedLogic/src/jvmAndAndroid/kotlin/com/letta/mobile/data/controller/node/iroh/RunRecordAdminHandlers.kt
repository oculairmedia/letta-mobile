package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.records.MAX_ID_LENGTH
import com.letta.mobile.data.controller.node.iroh.records.TextField
import com.letta.mobile.data.controller.node.iroh.records.InstantField
import com.letta.mobile.data.controller.node.iroh.records.WrapperRecordStore
import com.letta.mobile.data.controller.node.iroh.records.StoredLeaseRecord
import com.letta.mobile.data.controller.node.iroh.records.RecordedLeaseState
import com.letta.mobile.data.controller.node.iroh.records.invalid
import com.letta.mobile.data.controller.node.iroh.records.instant
import com.letta.mobile.data.controller.node.iroh.records.leaseState
import com.letta.mobile.data.controller.node.iroh.records.parseLease
import com.letta.mobile.data.controller.node.iroh.records.parseRun
import com.letta.mobile.data.controller.node.iroh.records.recordPage
import com.letta.mobile.data.controller.node.iroh.records.text
import com.letta.mobile.data.controller.node.iroh.records.wire
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun registerRunRecordHandlers(router: AdminRpcRouter, store: WrapperRecordStore) {
    router.registerScoped("run.record") { params, context ->
        requireRecordAuthentication(context)
        val input = parseRun(params)
        requireRecordAgentScope(context, AgentId(input.agentId))
        buildJsonObject { put("record", store.record(input).wire()) }
    }
    router.registerScoped("lease.upsert") { params, context ->
        requireRecordAuthentication(context)
        val input = parseLease(params)
        requireRecordAgentScope(context, AgentId(input.owner))
        val prior = store.snapshot().leases.firstOrNull { it.input.id == input.id }
        prior?.let { requireRecordAgentScope(context, AgentId(it.input.owner)) }
        buildJsonObject { put("lease", store.upsert(input).wire()) }
    }
    router.registerScoped("lease.list") { params, context ->
        requireRecordAuthentication(context)
        val agent = params.text(TextField("agentId", MAX_ID_LENGTH, optional = true))
        agent?.let { requireRecordAgentScope(context, AgentId(it)) }
        val state = params.text(TextField("state", MAX_ID_LENGTH, optional = true))?.let(::leaseState)
        val from = params.instant(InstantField("from", optional = true))
        val to = params.instant(InstantField("to", optional = true))
        if (invalidRange(from, to)) invalid("from/to")
        val filter = LeaseFilter(context, agent, state, from, to)
        val rows = store.snapshot().leases.filter(filter::includes)
            .sortedWith(compareByDescending<StoredLeaseRecord> { it.input.startedAtMs }
                .thenBy { it.input.id }).map { it.wire() }
        recordPage(rows, params, "leases")
    }
    router.featureCapabilities += setOf("run.record", "lease.upsert", "lease.list")
}

private fun invalidRange(from: Long?, to: Long?): Boolean = from?.let { start -> to?.let { start > it } } ?: false

private class LeaseFilter(
    private val context: AdminRpcRequestContext,
    private val agent: String?,
    private val state: RecordedLeaseState?,
    private val from: Long?,
    private val to: Long?,
) {
    fun includes(row: StoredLeaseRecord): Boolean =
        IrohPeerCapabilities.isAgentInScope(context.authorizedAgentIds, row.input.owner) &&
            matchesAgent(row) && matchesStateAndTime(row)

    private fun matchesAgent(row: StoredLeaseRecord): Boolean = agent == null || row.input.owner == agent

    private fun matchesStateAndTime(row: StoredLeaseRecord): Boolean =
        (state == null || row.input.state == state) && overlaps(row)

    private fun overlaps(row: StoredLeaseRecord): Boolean =
        (from == null || row.input.dueAtMs >= from) && startsBeforeEnd(row)

    private fun startsBeforeEnd(row: StoredLeaseRecord): Boolean = to == null || row.input.startedAtMs <= to
}

internal fun requireRecordAuthentication(context: AdminRpcRequestContext) {
    if (context.authenticated) return
    Telemetry.event("AdminRpc", "auth.required")
    adminError("unauthorized")
}

internal fun requireRecordAgentScope(context: AdminRpcRequestContext, agent: AgentId) {
    if (IrohPeerCapabilities.isAgentInScope(context.authorizedAgentIds, agent.value)) return
    Telemetry.event("AdminRpc", "authz.agent_scope_denied")
    adminError(AgentScopeGuard.DENIED)
}
