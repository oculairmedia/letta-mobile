package com.letta.mobile.data.repository

import com.letta.mobile.data.model.GoalStatusResponse
import com.letta.mobile.data.model.SlashCommand
import com.letta.mobile.data.model.SlashCommandsResponse
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.repository.api.SlashCommandIrohSource
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.backendUrlTelemetryDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Slash commands over the Iroh admin_rpc control channel.
 *
 * Phase 5o — mirrors [com.letta.mobile.data.api.SlashCommandApi]'s HTTP surface
 * without falling back to raw HTTP in iroh:// mode.
 */
open class IrohAdminRpcSlashCommandSource(
    private val channelTransport: IChannelTransport,
    private val settingsRepository: ISettingsRepository,
    private val deviceId: String,
    private val clientVersion: String,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) : SlashCommandIrohSource {
    override fun shouldUseIroh(): Boolean = settingsRepository.activeBackendIsIroh()

    override suspend fun listGlobal(): List<SlashCommand> =
        fetch(path = "/v1/slash-commands", method = "slash_command.list", agentId = null)

    override suspend fun listForAgent(agentId: String): List<SlashCommand> =
        fetch(
            path = "/v1/agents/$agentId/slash-commands",
            method = "slash_command.list_agent",
            agentId = agentId,
        )

    override suspend fun installToAgent(agentId: String, skillName: String) {
        mutateSkillOverIroh(
            method = "skill.install",
            path = "/v1/agents/$agentId/skills",
            agentId = agentId,
            skillName = skillName,
        )
    }

    override suspend fun uninstallFromAgent(agentId: String, skillName: String) {
        mutateSkillOverIroh(
            method = "skill.uninstall",
            path = "/v1/agents/$agentId/skills/$skillName",
            agentId = agentId,
            skillName = skillName,
        )
    }

    override suspend fun getGoalStatus(agentId: String): GoalStatusResponse {
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
        return json.decodeFromJsonElement(GoalStatusResponse.serializer(), result)
    }

    override suspend fun executeGoalCommand(agentId: String, command: String): String {
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
        return (body?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Goal command executed."
    }

    private suspend fun mutateSkillOverIroh(
        method: String,
        path: String,
        agentId: String,
        skillName: String,
    ) {
        ensureConnectedForAdminRpc()
        val body = JsonObject(
            mapOf(
                "agent_id" to JsonPrimitive(agentId),
                "name" to JsonPrimitive(skillName),
            ),
        ).toString()
        val response = channelTransport.adminRpc(method = method, path = path, body = body)
        if (!response.success) {
            throw IllegalStateException(response.error ?: "Iroh admin_rpc $method failed")
        }
    }

    private suspend fun fetch(
        path: String,
        method: String,
        agentId: String?,
    ): List<SlashCommand> {
        ensureConnectedForAdminRpc()
        val body = agentId?.let { JsonObject(mapOf("agent_id" to JsonPrimitive(it))).toString() } ?: "{}"
        val response = channelTransport.adminRpc(method = method, path = path, body = body)
        if (!response.success) {
            throw IllegalStateException(response.error ?: "Iroh admin_rpc $method failed")
        }
        val result = response.result ?: throw IllegalStateException("Iroh admin_rpc $method returned no result")
        return json.decodeFromJsonElement(SlashCommandsResponse.serializer(), result).commands
    }

    private suspend fun ensureConnectedForAdminRpc() {
        if (channelTransport.state.value is ChannelTransportState.Connected) return
        val config = settingsRepository.activeConfig.value
            ?: error("Iroh admin_rpc requested with no active backend config")
        val serverUrl = config.serverUrl
        if (!IrohChannelTransport.shouldUseIroh(serverUrl)) {
            error("Iroh admin_rpc requested while backend is not iroh://")
        }
        Telemetry.event(
            "IrohTransport", "adminRpc.ensureConnected",
            "serverUrl" to backendUrlTelemetryDescriptor(serverUrl),
            "state" to channelTransport.state.value::class.simpleName,
        )
        channelTransport.connect(
            baseShimUrl = serverUrl,
            token = config.accessToken.orEmpty(),
            deviceId = deviceId,
            clientVersion = clientVersion,
        )
        if (channelTransport.state.value !is ChannelTransportState.Connected) {
            error("Iroh admin_rpc could not connect transport")
        }
    }
}
