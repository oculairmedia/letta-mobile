package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Agent reads over the Iroh admin RPC control channel.
 *
 * letta-mobile-71orq: after P4 purity (letta-mobile-qfa81) the LettaApiClient
 * choke-point hard-fails any raw HTTP admin call in iroh:// mode instead of
 * silently falling back to a stale HTTP config. [AgentRepository.getAgent]
 * previously always called the raw HTTP [com.letta.mobile.data.api.AgentApi],
 * so opening a conversation over iroh:// threw
 * [com.letta.mobile.data.api.IrohAdminApiUnavailableException] before any
 * stream and the whole chat screen errored out.
 *
 * The server-side handlers already exist (AgentAdminHandlers registers
 * `agent.get` and `agent.list`); this is the missing CLIENT wiring, mirroring
 * [IrohAdminRpcConversationListSource].
 */
class IrohAdminRpcAgentSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    // Match the raw AgentApi Json config: the server may serialize optional
    // fields as explicit null (e.g. "metadata": null). explicitNulls=false +
    // coerceInputValues=true coerce those to the property defaults instead of
    // failing to decode (letta-mobile-71orq).
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    },
) {
    fun shouldUseIroh(): Boolean =
        settingsRepository.activeBackendIsIroh()

    /**
     * Update an agent over admin_rpc (server AgentAdminHandlers `agent.update`
     * proxies PATCH /v1/agents/{id}). The handler reads `agent_id` from params
     * and forwards the whole params object as the PATCH body; the raw
     * AgentUpdateParams JSON is merged with the id so unknown-body fields are
     * simply passed through.
     */
    suspend fun updateAgent(id: AgentId, paramsJson: String): Agent {
        val body = buildJsonObject {
            put("agent_id", id.value)
            val parsed = runCatching { json.parseToJsonElement(paramsJson) }.getOrNull()
            (parsed as? kotlinx.serialization.json.JsonObject)?.forEach { (key, value) ->
                if (key != "agent_id") put(key, value)
            }
        }
        val response = channelTransport.adminRpc(
            method = "agent.update",
            path = "/v1/agents/${id.value}",
            body = body.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc agent.update failed")
        val result = response.result ?: error("Iroh admin_rpc agent.update returned no result")
        return json.decodeFromJsonElement(Agent.serializer(), result)
    }

    suspend fun getContextWindow(agentId: AgentId, conversationId: ConversationId?): ContextWindowOverview {
        val params = buildJsonObject {
            put("agent_id", agentId.value)
            conversationId?.let { put("conversation_id", it.value) }
        }
        val path = buildString {
            append("/v1/agents/${agentId.value}/context")
            conversationId?.let { append("?conversation_id=${it.value}") }
        }
        val response = channelTransport.adminRpc(
            method = "agent.context",
            path = path,
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc agent.context failed")
        val result = response.result ?: error("Iroh admin_rpc agent.context returned no result")
        return json.decodeFromJsonElement(ContextWindowOverview.serializer(), result)
    }

    suspend fun getAgent(id: AgentId): Agent {
        val params = buildJsonObject { put("agent_id", id.value) }
        val response = channelTransport.adminRpc(
            method = "agent.get",
            path = "/v1/agents/${id.value}",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc agent.get failed")
        val result = response.result ?: error("Iroh admin_rpc agent.get returned no result")
        return json.decodeFromJsonElement(Agent.serializer(), result)
    }

    /**
     * Create an agent over admin_rpc (server AgentAdminHandlers `agent.create`
     * proxies POST /v1/agents). P4 purity client batch.
     */
    suspend fun createAgent(paramsJson: String): Agent {
        val response = channelTransport.adminRpc(
            method = "agent.create",
            path = "/v1/agents",
            body = paramsJson,
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc agent.create failed")
        val result = response.result ?: error("Iroh admin_rpc agent.create returned no result")
        return json.decodeFromJsonElement(Agent.serializer(), result)
    }

    /**
     * Delete an agent over admin_rpc (server AgentAdminHandlers `agent.delete`
     * proxies DELETE /v1/agents/{id}). P4 purity client batch.
     */
    suspend fun deleteAgent(id: AgentId) {
        val params = buildJsonObject { put("agent_id", id.value) }
        val response = channelTransport.adminRpc(
            method = "agent.delete",
            path = "/v1/agents/${id.value}",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc agent.delete failed")
    }

    /**
     * List ALL agents by paging through `agent.list`. The server returns only a
     * default first page (~50) when no limit is given, so agents beyond it never
     * resolved a name in the conversation list and fell back to `agentId.take(8)`
     * (letta-mobile-71orq).
     */
    suspend fun listAgents(): List<Agent> {
        val merged = mutableListOf<Agent>()
        val seenIds = HashSet<String>()
        var offset = 0
        // Hard cap on iterations so a server that ignores `offset` (returning the
        // same page) or otherwise never shortens can't loop forever
        // (CodeRabbit/Codex review on #818). The seenIds dedup already breaks on
        // a stalled offset (fresh.isEmpty), but the cap is a belt-and-braces
        // bound: MAX_PAGES * page size covers far more agents than realistic.
        var iterations = 0
        while (iterations < MAX_AGENT_LIST_PAGES) {
            iterations++
            val params = buildJsonObject {
                put("limit", AGENT_LIST_PAGE_SIZE.toString())
                put("offset", offset.toString())
            }
            val response = channelTransport.adminRpc(
                method = "agent.list",
                path = "/v1/agents?limit=$AGENT_LIST_PAGE_SIZE&offset=$offset",
                body = params.toString(),
            )
            if (!response.success) error(response.error ?: "Iroh admin_rpc agent.list failed")
            val result = response.result
            if (result == null) {
                // letta-mobile-z5lqt: telemetry only — the break is unchanged.
                RosterNameTelemetry.sweepStopped(
                    stop = RosterNameTelemetry.SweepStop.NO_RESULT,
                    offset = offset,
                    pageSize = 0,
                    mergedSize = merged.size,
                    source = TELEMETRY_SOURCE,
                )
                break
            }
            val page = json.decodeFromJsonElement(ListSerializer(Agent.serializer()), result)
            if (page.isEmpty()) {
                RosterNameTelemetry.sweepStopped(
                    stop = RosterNameTelemetry.SweepStop.EMPTY_PAGE,
                    offset = offset,
                    pageSize = 0,
                    mergedSize = merged.size,
                    source = TELEMETRY_SOURCE,
                )
                break
            }
            val fresh = page.filter { seenIds.add(it.id.value) }
            // Server ignored offset / returned an already-seen page: stop rather
            // than spin. Returns what we have so far (still better than page 1).
            if (fresh.isEmpty()) {
                RosterNameTelemetry.sweepStopped(
                    stop = RosterNameTelemetry.SweepStop.NO_FRESH_IGNORED_OFFSET,
                    offset = offset,
                    pageSize = page.size,
                    mergedSize = merged.size,
                    source = TELEMETRY_SOURCE,
                )
                break
            }
            merged += fresh
            if (page.size < AGENT_LIST_PAGE_SIZE) {
                RosterNameTelemetry.sweepStopped(
                    stop = RosterNameTelemetry.SweepStop.SHORT_PAGE,
                    offset = offset,
                    pageSize = page.size,
                    mergedSize = merged.size,
                    source = TELEMETRY_SOURCE,
                )
                break
            }
            offset += page.size
            if (iterations >= MAX_AGENT_LIST_PAGES) {
                // Loop is about to end on the cap with pages still full: the
                // roster is almost certainly truncated. WARN.
                RosterNameTelemetry.sweepStopped(
                    stop = RosterNameTelemetry.SweepStop.PAGE_CAP_EXHAUSTED,
                    offset = offset,
                    pageSize = page.size,
                    mergedSize = merged.size,
                    source = TELEMETRY_SOURCE,
                )
            }
        }
        reportRosterCompleteness(merged.size)
        return merged
    }

    /**
     * letta-mobile-z5lqt: compare the swept roster against the authoritative
     * `agent.count`. Observation only — the swept list is returned unchanged
     * whatever this reports, and a failure to measure is recorded as UNKNOWN
     * rather than being swallowed.
     */
    private suspend fun reportRosterCompleteness(sweptSize: Int) {
        if (!Telemetry.rosterCompletenessProbeEnabled.get()) return
        val authoritative = runCatching { fetchAuthoritativeAgentCount() }
        RosterNameTelemetry.rosterCompleteness(
            outcome = RosterNameTelemetry.classifyCompleteness(sweptSize, authoritative),
            source = TELEMETRY_SOURCE,
        )
    }

    /** @return the server-reported total, or null when it is unavailable. */
    private suspend fun fetchAuthoritativeAgentCount(): Int? {
        val response = channelTransport.adminRpc(
            method = "agent.count",
            path = "/v1/agents/count",
            body = "{}",
        )
        if (!response.success) return null
        val result = response.result ?: return null
        // `/v1/agents/count` returns a bare number; tolerate a `{"count": n}`
        // envelope too rather than misreporting a shape change as a mismatch.
        return (result as? JsonPrimitive)?.intOrNull
            ?: (result as? JsonObject)?.get("count")?.jsonPrimitive?.intOrNull
    }

    private companion object {
        // Kept modest so a single page stays comfortably under the ~1MB
        // unchunked Iroh frame cap even for agents with sizeable metadata
        // (Codex review on #818 — the same cap that broke message.list before).
        const val TELEMETRY_SOURCE = "IrohAdminRpcAgentSource"
        const val AGENT_LIST_PAGE_SIZE = 50
        // Belt-and-braces bound: 50 pages * 50 = 2500 agents, far above realistic
        // fleets; prevents an unbounded loop if the server misbehaves on offset.
        const val MAX_AGENT_LIST_PAGES = 50
    }
}
