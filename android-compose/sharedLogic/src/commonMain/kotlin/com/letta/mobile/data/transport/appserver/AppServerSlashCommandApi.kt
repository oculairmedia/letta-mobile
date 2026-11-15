package com.letta.mobile.data.transport.appserver

import com.letta.mobile.data.commands.api.SlashCommandQuery
import com.letta.mobile.data.commands.api.AgentSlashCommand
import com.letta.mobile.data.commands.api.SlashCommandsApi
import com.letta.mobile.data.commands.api.AppServerCommandDescriptions

/**
 * App Server implementation of [SlashCommandsApi].
 * Populates commands from the server's advertised supported commands.
 *
 * @constructor Binds discovery and execution to one session's transport.
 * @param execute Dispatches an execution request and waits for host confirmation.
 * @param supportedCommandsProvider Supplies built-in command names for native-only callers.
 * @param requestId Creates the correlation identity for each execution.
 * @param commandsProvider Supplies the host catalog including agent-specific mod metadata.
 */
open class AppServerSlashCommandApi(
    private val execute: suspend (AppServerCommand.ExecuteCommand) -> AppServerInboundFrame.ExecuteCommandResponse,
    private val supportedCommandsProvider: (suspend (String) -> List<String>)? = null,
    private val requestId: (String) -> String = { "cmd-$it" },
    private val commandsProvider: (suspend (String) -> List<AgentSlashCommand>)? = null,
) : SlashCommandsApi {
    /** Adapts a native client whose lifetime remains owned by its session. */
    constructor(
        client: AppServerClient,
        supportedCommandsProvider: (suspend (String) -> List<String>)? = null,
        requestId: (String) -> String = { "cmd-$it" },
    ) : this(client::executeCommand, supportedCommandsProvider, requestId)

    /** Reads the focused agent's host catalog, retaining descriptions and installation metadata. */
    override suspend fun listAgentSlashCommands(query: SlashCommandQuery): List<AgentSlashCommand> {
        commandsProvider?.let { return it(query.agentId) }
        val commands = supportedCommandsProvider?.invoke(query.agentId) ?: AppServerCommandDescriptions.DEFAULT_SUPPORTED_COMMANDS
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

    /** Executes a command name and arguments in the captured runtime, returning the host's answer. */
    suspend fun executeCommand(
        commandName: String,
        args: String? = null,
        runtime: AppServerRuntimeScope? = null,
    ): AppServerInboundFrame.ExecuteCommandResponse =
        execute(
            AppServerCommand.ExecuteCommand(
                requestId = requestId(commandName),
                commandId = commandName,
                args = args,
                runtime = runtime,
            ),
        )

    /** The adapter borrows its client; the session closes the underlying transport. */
    override fun close() = Unit

}
