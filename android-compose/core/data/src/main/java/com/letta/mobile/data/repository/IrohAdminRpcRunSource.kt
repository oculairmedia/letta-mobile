package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Run
import com.letta.mobile.data.model.Step
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    suspend fun listRuns(): List<Run> {
        val response = channelTransport.adminRpc(
            method = "run.list",
            path = "/v1/runs",
            body = "{}",
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc run.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Run.serializer()), result)
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
