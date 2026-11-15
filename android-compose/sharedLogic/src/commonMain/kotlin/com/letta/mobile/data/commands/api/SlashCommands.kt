package com.letta.mobile.data.commands.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Commands advertised for the focused agent; selection fills the composer for submission. */
interface SlashCommandsApi : AutoCloseable {
    /** Reads the agent's host catalog, including mod metadata when the host supplies it. */
    suspend fun listAgentSlashCommands(query: SlashCommandQuery): List<AgentSlashCommand>
    override fun close() = Unit
}

/** A host-advertised command with its original label, description and installation metadata. */
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

/** Built-in descriptions used when a host advertises command ids without labels. */
object AppServerCommandDescriptions {
    /** Built-in command ids used until a device status advertises a catalog. */
    val DEFAULT_SUPPORTED_COMMANDS: List<String> = listOf(
        "clear", "clear-messages", "doctor", "dream", "reflect",
        "init", "compact", "reload", "context-limit", "channels",
        "upgrade-letta-code", "toolset", "secret", "monitor_stop",
    )

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

    fun descriptionFor(commandName: String): String =
        DESCRIPTIONS[commandName.removePrefix("/")] ?: "App Server command"
}


/** Agent-scoped wire query; keeps raw protocol identity at the request boundary. */
data class SlashCommandQuery(val agentId: String)
