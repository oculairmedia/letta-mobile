package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.RunListParams
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Run reads over the Iroh admin RPC control channel.
 *
 * P4 purity client batch (letta-mobile): server handlers exist (RunAdminHandlers
 * registers run.list, run.get, step.list); this is the client wiring.
 */
class IrohAdminRpcRunSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
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
     * Encodes every [RunListParams] field the server's `run.list` admin_rpc
     * handler (`RunAdminHandlers` / admin-shim `handleRunsList`) understands,
     * omitting nulls. The server only supports a single `agent_id` (not a
     * list) and orders results by a fixed `created_at` column, so params that
     * imply anything else — non-singleton `agentIds`, a conflicting `agentId` /
     * `ascending` override, or an `orderBy` other than `created_at` — are
     * rejected loudly rather than silently dropped or ignored.
     */
    suspend fun listRuns(params: RunListParams = RunListParams()): List<Run> {
        val agentId = resolveAgentId(params)
        val order = resolveOrder(params)
        require(params.orderBy == null || params.orderBy == "created_at") {
            "Iroh run.list only supports order_by=created_at (server ordering is fixed); got '${params.orderBy}'"
        }

        val body = buildJsonObject {
            agentId?.let { put("agent_id", it) }
            params.conversationId?.let { put("conversation_id", it) }
            params.active?.let { put("active", it) }
            params.background?.let { put("background", it) }
            params.statuses?.let { statuses ->
                putJsonArray("statuses") { statuses.forEach { add(JsonPrimitive(it)) } }
            }
            params.stopReason?.let { put("stop_reason", it) }
            params.before?.let { put("before", it) }
            params.after?.let { put("after", it) }
            params.limit?.let { put("limit", it) }
            order?.let { put("order", it) }
            params.orderBy?.let { put("order_by", it) }
        }
        val response = channelTransport.adminRpc(
            method = "run.list",
            path = "/v1/runs",
            body = body.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc run.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Run.serializer()), result)
    }

    /** Singleton `agent_ids` maps onto the server's scalar `agent_id`; anything else is unsupported. */
    private fun resolveAgentId(params: RunListParams): String? {
        val agentIds = params.agentIds ?: return params.agentId
        require(agentIds.size == 1) {
            "Iroh run.list supports only a single agent_id; got ${agentIds.size} in agentIds"
        }
        val fromList = agentIds.first()
        require(params.agentId == null || params.agentId == fromList) {
            "Iroh run.list got conflicting agentId='${params.agentId}' and agentIds=$agentIds"
        }
        return fromList
    }

    /** Explicit `order` is case-insensitive and normalized; conflicts with `ascending` are rejected. */
    private fun resolveOrder(params: RunListParams): String? {
        val explicitOrder = params.order?.lowercase()
        require(explicitOrder == null || explicitOrder == "asc" || explicitOrder == "desc") {
            "Iroh run.list order must be 'asc' or 'desc' (case-insensitive); got '${params.order}'"
        }
        val fromAscending = params.ascending?.let { if (it) "asc" else "desc" }
        if (fromAscending == null) return explicitOrder
        require(explicitOrder == null || explicitOrder == fromAscending) {
            "Iroh run.list got conflicting order='${params.order}' and ascending=${params.ascending}"
        }
        return fromAscending
    }

    suspend fun getRun(runId: String): Run {
        val params = buildJsonObject { put("run_id", runId) }
        val response = channelTransport.adminRpc(
            method = "run.get",
            path = "/v1/runs/$runId",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc run.get failed")
        val result = response.result ?: error("Iroh admin_rpc run.get returned no result")
        return json.decodeFromJsonElement(Run.serializer(), result)
    }

    suspend fun getRunSteps(runId: String): List<Step> {
        val params = buildJsonObject { put("run_id", runId) }
        val response = channelTransport.adminRpc(
            method = "step.list",
            path = "/v1/runs/$runId/steps",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc step.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Step.serializer()), result)
    }
}
