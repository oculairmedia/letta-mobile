package com.letta.mobile.clientmode

import com.letta.mobile.bot.protocol.BotAgentInfo
import com.letta.mobile.bot.protocol.WsBotClient
import com.letta.mobile.bot.protocol.preferredAgent

suspend fun resolveClientModeRemoteAgent(
    baseUrl: String,
    apiKey: String?,
): BotAgentInfo {
    WsBotClient(baseUrl = baseUrl, apiKey = apiKey).use { client ->
        return client.getStatus().preferredAgent()
            ?: throw IllegalStateException("Remote bot status did not expose any agents")
    }
}
