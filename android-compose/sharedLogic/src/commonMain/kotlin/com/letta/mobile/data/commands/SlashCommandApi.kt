package com.letta.mobile.data.commands

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
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
 * Lives in commonMain; HTTP hosts use [SlashCommandApi], iroh:// hosts use
 * [com.letta.mobile.data.repository.iroh.IrohSlashCommandApi].
 */
interface SlashCommandsApi : AutoCloseable {
    suspend fun listAgentSlashCommands(agentId: String): List<AgentSlashCommand>
    override fun close() = Unit
}

class SlashCommandApi(
    private val config: LettaConfig,
    private val httpClient: HttpClient,
) : SlashCommandsApi {
    private val baseUrl = config.serverUrl.trimEnd('/')

    override suspend fun listAgentSlashCommands(agentId: String): List<AgentSlashCommand> {
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
internal data class SlashCommandsResponse(
    val commands: List<AgentSlashCommand> = emptyList(),
)

/**
 * App Server implementation of [SlashCommandsApi] (letta-mobile-bzvro.20).
 * Populates commands from the server's advertised supported commands.
 */
class AppServerSlashCommandApi(
    private val client: AppServerClient,
    private val supportedCommandsProvider: (suspend (String) -> List<String>)? = null,
    private val requestId: (String) -> String = { "cmd-$it" },
) : SlashCommandsApi {
    override suspend fun listAgentSlashCommands(agentId: String): List<AgentSlashCommand> {
        val commands = supportedCommandsProvider?.invoke(agentId) ?: DEFAULT_SUPPORTED_COMMANDS
        return commands.map { cmd ->
            AgentSlashCommand(
                rawCommand = "/$cmd",
                name = cmd,
                description = AppServerCommandDescriptions.descriptionFor(cmd),
                source = "appserver",
                installed = false,
            )
        }
    }

    suspend fun executeCommand(
        commandId: String,
        args: String? = null,
        runtime: AppServerRuntimeScope? = null,
    ): AppServerInboundFrame.ExecuteCommandResponse =
        client.executeCommand(
            AppServerCommand.ExecuteCommand(
                requestId = requestId(commandId),
                commandId = commandId,
                args = args,
                runtime = runtime,
            ),
        )

    override fun close() = Unit

    companion object {
        val DEFAULT_SUPPORTED_COMMANDS: List<String> = listOf(
            "clear", "clear-messages", "doctor", "dream", "reflect",
            "init", "compact", "reload", "context-limit", "channels",
            "upgrade-letta-code", "toolset", "secret", "monitor_stop",
        )
    }
}

object AppServerCommandDescriptions {
    private val DESCRIPTIONS: Map<String, String> = mapOf(
        "clear" to "Clear current chat session",
        "clear-messages" to "Clear message history",
        "doctor" to "Run environment diagnostics",
        "dream" to "Consolidate memory",
        "reflect" to "Reflect on recent interactions",
        "init" to "Initialize agent state and memory",
        "compact" to "Compact conversation history",
        "reload" to "Reload agent skills and tools",
        "context-limit" to "Inspect or adjust context window limits",
        "channels" to "Manage communication channels",
        "upgrade-letta-code" to "Check for or apply Letta Code updates",
        "toolset" to "Select active toolset",
        "secret" to "Manage agent secrets",
        "monitor_stop" to "Stop a running background monitor",
    )

    fun descriptionFor(commandId: String): String =
        DESCRIPTIONS[commandId.removePrefix("/")] ?: "App Server command"
}

