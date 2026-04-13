package com.letta.mobile.bot.tools

import com.letta.mobile.bot.runtime.LettaRuntimeClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BotToolSync @Inject constructor(
    private val runtimeClient: LettaRuntimeClient,
    private val toolRegistry: BotToolRegistry,
) {
    suspend fun syncTools(agentId: String) {
        toolRegistry.listToolCreateParams().forEach { params ->
            val tool = runtimeClient.upsertTool(params)
            runtimeClient.attachTool(agentId, tool.id)
        }
    }
}
