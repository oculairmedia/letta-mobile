package com.letta.mobile.data.repository

import com.letta.mobile.data.model.Job
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Job reads over the Iroh admin RPC control channel.
 *
 * P4 purity client batch (letta-mobile): server handlers exist (ScheduleAdminHandlers
 * registers job.list, job.get); this is the client wiring.
 */
class IrohAdminRpcJobSource(
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

    suspend fun listJobs(): List<Job> {
        val response = channelTransport.adminRpc(
            method = "job.list",
            path = "/v1/jobs",
            body = "{}",
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc job.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Job.serializer()), result)
    }

    suspend fun getJob(jobId: String): Job {
        val params = buildJsonObject { put("job_id", jobId) }
        val response = channelTransport.adminRpc(
            method = "job.get",
            path = "/v1/jobs/$jobId",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc job.get failed")
        val result = response.result ?: error("Iroh admin_rpc job.get returned no result")
        return json.decodeFromJsonElement(Job.serializer(), result)
    }
}
