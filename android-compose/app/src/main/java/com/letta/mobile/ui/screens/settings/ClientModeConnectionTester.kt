package com.letta.mobile.ui.screens.settings

import com.letta.mobile.bot.protocol.WsBotClient
import com.letta.mobile.clientmode.resolveClientModeRemoteAgent
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.coroutines.withTimeout

@ViewModelScoped
class ClientModeConnectionTester @Inject constructor() {
    suspend fun test(baseUrl: String, apiKey: String?): Result<Unit> = runCatching {
        withTimeout(10_000) {
            val remoteAgent = resolveClientModeRemoteAgent(baseUrl = baseUrl, apiKey = apiKey?.takeIf { it.isNotBlank() })
            WsBotClient(baseUrl = baseUrl, apiKey = apiKey?.takeIf { it.isNotBlank() }).use { client ->
                client.getStatus()
                client.ensureGatewayReady(remoteAgent.id)
            }
        }
    }.map { Unit }
}
