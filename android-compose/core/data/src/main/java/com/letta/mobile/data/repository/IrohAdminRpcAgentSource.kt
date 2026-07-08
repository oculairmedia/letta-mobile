package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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
        IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)

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
            val result = response.result ?: break
            val page = json.decodeFromJsonElement(ListSerializer(Agent.serializer()), result)
            if (page.isEmpty()) break
            val fresh = page.filter { seenIds.add(it.id.value) }
            // Server ignored offset / returned an already-seen page: stop rather
            // than spin. Returns what we have so far (still better than page 1).
            if (fresh.isEmpty()) break
            merged += fresh
            if (page.size < AGENT_LIST_PAGE_SIZE) break
            offset += page.size
        }
        return merged
    }

    private companion object {
        // Kept modest so a single page stays comfortably under the ~1MB
        // unchunked Iroh frame cap even for agents with sizeable metadata
        // (Codex review on #818 — the same cap that broke message.list before).
        const val AGENT_LIST_PAGE_SIZE = 50
        // Belt-and-braces bound: 50 pages * 50 = 2500 agents, far above realistic
        // fleets; prevents an unbounded loop if the server misbehaves on offset.
        const val MAX_AGENT_LIST_PAGES = 50
    }
}
