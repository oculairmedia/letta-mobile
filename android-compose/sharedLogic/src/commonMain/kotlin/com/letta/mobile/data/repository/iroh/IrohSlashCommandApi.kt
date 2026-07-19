package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.commands.AgentSlashCommand
import com.letta.mobile.data.commands.SlashCommandsApi

class IrohSlashCommandApi(
    private val directory: IrohAdminRpcAgentDirectory,
) : SlashCommandsApi {
    override suspend fun listAgentSlashCommands(agentId: String): List<AgentSlashCommand> =
        directory.listAgentSlashCommands(agentId)
}
