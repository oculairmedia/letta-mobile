package com.letta.mobile.data.commands

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Platform-neutral slash-command API: an agent's slash commands. Selecting one fills the composer
 * with its command text so the user can add args and send.
 *
 * A direct App Server uses [AppServerSlashCommandApi] (its supported `execute_command` ids);
 * iroh:// hosts use [com.letta.mobile.data.repository.iroh.IrohSlashCommandApi].
 * removed the Letta REST implementation (`/v1/agents/{id}/slash-commands`).
 */
interface SlashCommandsApi : AutoCloseable {
    suspend fun listAgentSlashCommands(agentId: String): List<AgentSlashCommand>
    override fun close() = Unit
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
 * App Server implementation of [SlashCommandsApi].
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

