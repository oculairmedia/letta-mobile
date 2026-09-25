package com.letta.mobile.data.repository.appserver

import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.repository.api.AgentBlockTarget
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface AppServerLocalRepositoryTransport {
    suspend fun listAgents(): JsonArray
    suspend fun getContext(agentId: String, conversationId: String?): JsonObject?
    suspend fun listAgentBlocks(agentId: String): JsonArray

    /** `block.update_agent`: the App Server writes and commits `memory/system/<label>.md`. */
    suspend fun updateAgentBlock(target: AgentBlockTarget, params: BlockUpdateParams): JsonElement

    /** bfooy.5 `block.create_agent`: a new committed `memory/system/<label>.md`. */
    suspend fun createAgentBlock(target: AgentBlockTarget, params: BlockUpdateParams): JsonElement

    /** bfooy.5 `block.delete_agent`: a committed delete of `memory/system/<label>.md`. */
    suspend fun deleteAgentBlock(target: AgentBlockTarget)
}

class DefaultAppServerLocalRepositoryTransport(
    private val clientProvider: suspend () -> AppServerClient,
    private val requestId: (String) -> String,
) : AppServerLocalRepositoryTransport {
    override suspend fun listAgents(): JsonArray {
        val response = clientProvider().agentList(
            AppServerCommand.AgentList(
                requestId = requestId("agent-list"),
                query = buildJsonObject {
                    put("limit", "10000")
                    put("offset", "0")
                },
            ),
        )
        check(response.success) { response.error ?: "Bundled App Server agent listing failed" }
        return response.agents ?: error("Bundled App Server agent listing returned no agents")
    }

    override suspend fun getContext(agentId: String, conversationId: String?): JsonObject? =
        adminRpc(
            LocalAdminCall(
                operation = "agent-context",
                method = "agent.context",
                params = buildJsonObject {
                    put("agent_id", agentId)
                    conversationId?.let { put("conversation_id", it) }
                },
            ),
        ) as? JsonObject

    override suspend fun listAgentBlocks(agentId: String): JsonArray {
        val merged = mutableListOf<JsonElement>()
        var offset = 0
        repeat(BLOCK_LIST_MAX_PAGES) {
            val result = adminRpc(
                LocalAdminCall(
                    operation = "block-list",
                    method = "block.list_agent",
                    params = buildJsonObject {
                        put("agent_id", agentId)
                        put("limit", BLOCK_LIST_PAGE_SIZE.toString())
                        put("offset", offset.toString())
                    },
                ),
            ) ?: error("Bundled App Server block listing returned no result")
            if (result is JsonArray) return JsonArray(merged + result)
            val page = result as? JsonObject
                ?: error("Bundled App Server block listing returned an unsupported result")
            val blocks = page["blocks"] as? JsonArray
                ?: error("Bundled App Server block listing returned no blocks")
            val hasMore = (page["has_more"] as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.booleanOrNull
                ?: error("Bundled App Server block listing returned invalid has_more")
            merged.addAll(blocks)
            if (!hasMore) return JsonArray(merged)
            check(blocks.isNotEmpty()) { "Bundled App Server block listing returned an empty continuing page" }
            offset += blocks.size
        }
        error("Bundled App Server block listing exceeded $BLOCK_LIST_MAX_PAGES pages")
    }

    override suspend fun updateAgentBlock(target: AgentBlockTarget, params: BlockUpdateParams): JsonElement {
        val value = requireNotNull(params.value) { "block.update_agent writes the memory file contents; value is required" }
        return adminRpc(target.call(AgentBlockCall.Update, value))
            ?: error("Bundled App Server block update returned no result")
    }

    override suspend fun createAgentBlock(target: AgentBlockTarget, params: BlockUpdateParams): JsonElement =
        adminRpc(target.call(AgentBlockCall.Create, params.value.orEmpty()))
            ?: error("Bundled App Server block create returned no result")

    override suspend fun deleteAgentBlock(target: AgentBlockTarget) {
        adminRpc(target.call(AgentBlockCall.Delete, value = null))
    }

    /** An agent-scoped block call: always agent + label, plus the value for writes. */
    private fun AgentBlockTarget.call(kind: AgentBlockCall, value: String?) = LocalAdminCall(
        operation = kind.operation,
        method = kind.method,
        params = buildJsonObject {
            put("agent_id", agentId)
            put("label", label)
            value?.let { put("value", it) }
        },
    )

    private enum class AgentBlockCall(val operation: String, val method: String) {
        Update("block-update", "block.update_agent"),
        Create("block-create", "block.create_agent"),
        Delete("block-delete", "block.delete_agent"),
    }

    private suspend fun adminRpc(call: LocalAdminCall): JsonElement? {
        val response = clientProvider().adminRpc(
            AppServerCommand.AdminRpc(
                requestId = requestId(call.operation),
                method = call.method,
                params = call.params,
            ),
        )
        check(response.success) { response.error ?: "Bundled App Server ${call.operation} failed" }
        return response.result
    }

    /** One admin_rpc request: [operation] names the request id prefix and error text. */
    private class LocalAdminCall(val operation: String, val method: String, val params: JsonObject)

    private companion object {
        const val BLOCK_LIST_PAGE_SIZE = 50
        const val BLOCK_LIST_MAX_PAGES = 100
    }
}
