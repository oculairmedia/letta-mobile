package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.GoalStatusResponse
import com.letta.mobile.data.model.SlashCommand

/**
 * HTTP slash-command admin surface used by
 * [com.letta.mobile.data.repository.CachedSlashCommandRepository].
 */
interface SlashCommandRemoteSource {
    suspend fun listGlobal(): List<SlashCommand>

    suspend fun listForAgent(agentId: String): List<SlashCommand>

    suspend fun installToAgent(agentId: String, skillName: String)

    suspend fun uninstallFromAgent(agentId: String, skillName: String)

    suspend fun getGoalStatus(agentId: String): GoalStatusResponse

    suspend fun executeGoalCommand(agentId: String, command: String): String
}

/**
 * Iroh admin_rpc slash-command surface. Implemented by
 * [com.letta.mobile.data.repository.IrohAdminRpcSlashCommandSource].
 */
interface SlashCommandIrohSource {
    fun shouldUseIroh(): Boolean

    suspend fun listGlobal(): List<SlashCommand>

    suspend fun listForAgent(agentId: String): List<SlashCommand>

    suspend fun installToAgent(agentId: String, skillName: String)

    suspend fun uninstallFromAgent(agentId: String, skillName: String)

    suspend fun getGoalStatus(agentId: String): GoalStatusResponse

    suspend fun executeGoalCommand(agentId: String, command: String): String
}
