package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.node.iroh.records.MAX_ID_LENGTH
import com.letta.mobile.data.controller.node.iroh.records.WrapperRecordStore
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
        val agent = params.text("agentId", MAX_ID_LENGTH, optional = true)
        agent?.let { requireRecordAgentScope(context, AgentId(it)) }
        val state = params.text("state", MAX_ID_LENGTH, optional = true)?.let(::leaseState)
        val from = params.instant("from", optional = true)
        val to = params.instant("to", optional = true)
        if (from != null && to != null && from > to) invalid("from/to")
        val rows = store.snapshot().leases.filter {
            IrohPeerCapabilities.isAgentInScope(context.authorizedAgentIds, it.input.owner) &&
                (agent == null || it.input.owner == agent) && (state == null || it.input.state == state) &&
                (from == null || it.input.dueAtMs >= from) && (to == null || it.input.startedAtMs <= to)
        }.sortedWith(compareByDescending<com.letta.mobile.data.controller.node.iroh.records.StoredLeaseRecord> { it.input.startedAtMs }
            .thenBy { it.input.id }).map { it.wire() }
        recordPage(rows, params, "leases")
    }
    router.featureCapabilities += setOf("run.record", "lease.upsert", "lease.list")
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
