package com.letta.mobile.data.commands

import com.letta.mobile.data.model.LettaConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Platform-neutral slash-command API. The server exposes per-agent slash
 * commands at `GET /v1/agents/{id}/slash-commands` (builtins like `/goal` plus
 * any installed skill's commands). Selecting one fills the composer with its
 * command text so the user can add args and send.
 *
 * Lives in commonMain; the platform supplies the Ktor [HttpClient].
 */
class SlashCommandApi(
    private val config: LettaConfig,
    private val httpClient: HttpClient,
) : AutoCloseable {
    private val baseUrl = config.serverUrl.trimEnd('/')

    suspend fun listAgentSlashCommands(agentId: String): List<AgentSlashCommand> {
        val response = httpClient.get("$baseUrl/v1/agents/$agentId/slash-commands") { applyAuth() }
        response.requireSuccess()
        return response.body<SlashCommandsResponse>().commands
    }

    override fun close() {
        httpClient.close()
    }

    private fun HttpRequestBuilder.applyAuth() {
        config.accessToken?.trim()?.takeIf { it.isNotBlank() }?.let(::bearerAuth)
    }

    private suspend fun HttpResponse.requireSuccess() {
        if (status.value !in 200..299) {
            throw IllegalStateException("Slash command API ${status.value}: ${bodyAsText()}")
        }
    }
}

@Serializable
data class AgentSlashCommand(
    @SerialName("command") val rawCommand: String,
    val name: String = "",
    val description: String = "",
    @SerialName("skill_name") val skillName: String? = null,
    val source: String = "",
    val installed: Boolean = false,
) {
    /** The command without its leading slash, for matching and composer insertion. */
    val command: String get() = rawCommand.trim().removePrefix("/")
}

@Serializable
private data class SlashCommandsResponse(
    val commands: List<AgentSlashCommand> = emptyList(),
)
