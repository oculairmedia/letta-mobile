package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.SlashCommand

interface ISlashCommandRepository {
    suspend fun listGlobal(): Result<List<SlashCommand>>
    suspend fun listForAgent(agentId: String): Result<List<SlashCommand>>
    suspend fun installToAgent(agentId: String, skillName: String): Result<Unit>
    suspend fun uninstallFromAgent(agentId: String, skillName: String): Result<Unit>
    suspend fun getGoalStatus(agentId: String): Result<com.letta.mobile.data.model.GoalStatusResponse>
    suspend fun executeGoalCommand(agentId: String, command: String): Result<String>
}
