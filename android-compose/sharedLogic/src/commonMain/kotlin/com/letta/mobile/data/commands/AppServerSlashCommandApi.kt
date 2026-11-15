package com.letta.mobile.data.commands

import com.letta.mobile.data.transport.appserver.AppServerClient

/** Native-client compatibility constructor; session graphs own the shared adapter. */
class AppServerSlashCommandApi(
    client: AppServerClient,
    supportedCommandsProvider: (suspend (String) -> List<String>)? = null,
    requestId: (String) -> String = { "cmd-$it" },
) : com.letta.mobile.data.transport.appserver.AppServerSlashCommandApi(client, supportedCommandsProvider, requestId) {
    companion object {
        val DEFAULT_SUPPORTED_COMMANDS: List<String> = AppServerCommandDescriptions.DEFAULT_SUPPORTED_COMMANDS
    }
}

/** Shared listing contract retained for existing callers. */
typealias SlashCommandsApi = com.letta.mobile.data.commands.api.SlashCommandsApi
/** A command advertised by the host. */
typealias AgentSlashCommand = com.letta.mobile.data.commands.api.AgentSlashCommand
/** Descriptions for advertised command names. */
typealias AppServerCommandDescriptions = com.letta.mobile.data.commands.api.AppServerCommandDescriptions

internal typealias SlashCommandsResponse = com.letta.mobile.data.commands.api.SlashCommandsResponse
