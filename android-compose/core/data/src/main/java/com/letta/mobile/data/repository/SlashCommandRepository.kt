package com.letta.mobile.data.repository

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.GoalStatusResponse
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.model.SlashCommandsResponse
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.ISlashCommandRepository
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.util.Telemetry
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SlashCommandRepository @Inject constructor(
    private val apiClient: LettaApiClient,
    private val settingsRepository: ISettingsRepository,
    private val channelTransport: IChannelTransport,
) : ISlashCommandRepository {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    override suspend fun listGlobal(): Result<List<SlashCommand>> =
        fetch("/v1/slash-commands", method = "slash_command.list", agentId = null)

    override suspend fun listForAgent(agentId: String): Result<List<SlashCommand>> =
        fetch("/v1/agents/$agentId/slash-commands", method = "slash_command.list_agent", agentId = agentId)

    override suspend fun installToAgent(agentId: String, skillName: String): Result<Unit> = runCatching {
        val (client, baseUrl) = apiClient.session()
        val response = client.post("$baseUrl/v1/agents/$agentId/skills") {
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("name" to JsonPrimitive(skillName))))
        }
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        Unit
    }.onFailure { if (it is CancellationException) throw it }

    override suspend fun uninstallFromAgent(agentId: String, skillName: String): Result<Unit> = runCatching {
        val (client, baseUrl) = apiClient.session()
        val encoded = skillName.encodeURLPathPart()
        val response = client.delete("$baseUrl/v1/agents/$agentId/skills/$encoded")
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        Unit
    }.onFailure { if (it is CancellationException) throw it }

    override suspend fun getGoalStatus(agentId: String): Result<GoalStatusResponse> = runCatching {
        if (shouldUseIroh()) {
            ensureConnectedForAdminRpc()
            val response = channelTransport.adminRpc(
                method = "goal.get",
                path = "/v1/agents/$agentId/goal",
                body = JsonObject(mapOf("agent_id" to JsonPrimitive(agentId))).toString(),
            )
            if (!response.success) {
                throw IllegalStateException(response.error ?: "Iroh admin_rpc goal.get failed")
            }
            val result = response.result ?: throw IllegalStateException("Iroh admin_rpc goal.get returned no result")
            return@runCatching json.decodeFromJsonElement(GoalStatusResponse.serializer(), result)
        }

        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl/v1/agents/$agentId/goal")
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        response.body<GoalStatusResponse>()
    }.onFailure { if (it is CancellationException) throw it }

    override suspend fun executeGoalCommand(agentId: String, command: String): Result<String> = runCatching {
        if (shouldUseIroh()) {
            ensureConnectedForAdminRpc()
            val response = channelTransport.adminRpc(
                method = "goal.command",
                path = "/v1/agents/$agentId/goal/command",
                body = JsonObject(
                    mapOf(
                        "agent_id" to JsonPrimitive(agentId),
                        "command" to JsonPrimitive(command),
                    ),
                ).toString(),
            )
            if (!response.success) {
                throw IllegalStateException(response.error ?: "Iroh admin_rpc goal.command failed")
            }
            val body = response.result?.jsonObject
            return@runCatching (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Goal command executed."
        }

        val (client, baseUrl) = apiClient.session()
        val response = client.post("$baseUrl/v1/agents/$agentId/goal/command") {
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("command" to JsonPrimitive(command))))
        }
        val bodyText = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw IllegalStateException(bodyText)
        }
        val body = runCatching { json.parseToJsonElement(bodyText).jsonObject }.getOrNull()
        (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Goal command executed."
    }.onFailure { if (it is CancellationException) throw it }

    private fun shouldUseIroh(): Boolean =
        IrohChannelTransport.shouldUseIroh(settingsRepository.activeConfig.value?.serverUrl)

    private suspend fun ensureConnectedForAdminRpc() {
        if (channelTransport.state.value is ChannelTransportState.Connected) return
        val config = settingsRepository.activeConfig.value
            ?: error("Iroh goal admin_rpc requested with no active backend config")
        val serverUrl = config.serverUrl
        if (!IrohChannelTransport.shouldUseIroh(serverUrl)) {
            error("Iroh goal admin_rpc requested while backend is not iroh://")
        }
        Telemetry.event(
            "IrohTransport", "goal.ensureConnected",
            "serverUrl" to serverUrl,
            "state" to channelTransport.state.value::class.simpleName,
        )
        channelTransport.connect(
            baseShimUrl = serverUrl,
            token = config.accessToken.orEmpty(),
            deviceId = "android-letta-mobile",
            clientVersion = "android-iroh-goal-admin-rpc",
        )
        if (channelTransport.state.value !is ChannelTransportState.Connected) {
            error("Iroh goal admin_rpc could not connect transport")
        }
    }

    private suspend fun fetch(
        path: String,
        method: String,
        agentId: String?,
    ): Result<List<SlashCommand>> = runCatching {
        // P4 iroh purity: the raw HTTP apiClient.session() hard-fails in iroh://
        // mode, so the slash-command list never loaded over Iroh. Route over
        // admin_rpc, mirroring getGoalStatus above.
        if (shouldUseIroh()) {
            ensureConnectedForAdminRpc()
            val body = agentId?.let { JsonObject(mapOf("agent_id" to JsonPrimitive(it))).toString() } ?: "{}"
            val response = channelTransport.adminRpc(method = method, path = path, body = body)
            if (!response.success) {
                throw IllegalStateException(response.error ?: "Iroh admin_rpc $method failed")
            }
            val result = response.result ?: throw IllegalStateException("Iroh admin_rpc $method returned no result")
            return@runCatching json.decodeFromJsonElement(SlashCommandsResponse.serializer(), result).commands
        }

        val (client, baseUrl) = apiClient.session()
        val response = client.get("$baseUrl$path")
        if (response.status.value !in 200..299) {
            throw IllegalStateException(response.bodyAsText())
        }
        response.body<SlashCommandsResponse>().commands
    }.onFailure { if (it is CancellationException) throw it }
}
