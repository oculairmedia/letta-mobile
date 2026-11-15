package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.commands.api.SlashCommandQuery
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.commands.SlashCommandsApi

class IrohSlashCommandApi(
    private val directory: IrohAdminRpcAgentDirectory,
) : SlashCommandsApi {
    override suspend fun listAgentSlashCommands(query: SlashCommandQuery): List<AgentSlashCommand> =
        directory.listAgentSlashCommands(AgentId(query.agentId))
}

/** Converts an advertised command to the repository row without losing its host metadata. */
fun AgentSlashCommand.toSlashCommand(): com.letta.mobile.data.model.SlashCommand = com.letta.mobile.data.model.SlashCommand(
    name = name, command = rawCommand, description = description, skillName = skillName, source = source, installed = installed,
)
