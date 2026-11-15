package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.commands.SlashCommandsApi
import com.letta.mobile.data.model.AgentId

class IrohSlashCommandApi(
    private val directory: IrohAdminRpcAgentDirectory,
) : SlashCommandsApi {
    override suspend fun listAgentSlashCommands(agentId: String): List<AgentSlashCommand> =
        directory.listAgentSlashCommands(AgentId(agentId))
}

/** Converts an advertised command to the repository row without losing its host metadata. */
fun AgentSlashCommand.toSlashCommand(): com.letta.mobile.data.model.SlashCommand = com.letta.mobile.data.model.SlashCommand(
    name = name, command = rawCommand, description = description, skillName = skillName, source = source, installed = installed,
)
