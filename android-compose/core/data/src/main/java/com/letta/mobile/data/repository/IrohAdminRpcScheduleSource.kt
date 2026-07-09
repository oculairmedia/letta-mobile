package com.letta.mobile.data.repository

import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class IrohAdminRpcScheduleSource(
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
        IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)

    suspend fun listSchedules(): List<ScheduledMessage> {
        val response = channelTransport.adminRpc(
            method = "schedule.list",
            path = "/v1/schedules",
            body = "{}",
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc schedule.list failed")
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(ScheduledMessage.serializer()), result)
    }

    suspend fun createSchedule(params: ScheduleCreateParams): ScheduledMessage {
        val response = channelTransport.adminRpc(
            method = "schedule.create",
            path = "/v1/schedules",
            body = json.encodeToString(ScheduleCreateParams.serializer(), params),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc schedule.create failed")
        val result = response.result ?: error("Iroh admin_rpc schedule.create returned no result")
        return json.decodeFromJsonElement(ScheduledMessage.serializer(), result)
    }

    suspend fun deleteSchedule(scheduleId: String) {
        val params = buildJsonObject { put("schedule_id", scheduleId) }
        val response = channelTransport.adminRpc(
            method = "schedule.delete",
            path = "/v1/schedules/$scheduleId",
            body = params.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc schedule.delete failed")
    }
}
