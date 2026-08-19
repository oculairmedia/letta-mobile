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
