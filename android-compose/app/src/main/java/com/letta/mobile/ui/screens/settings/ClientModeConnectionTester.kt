package com.letta.mobile.ui.screens.settings

import com.letta.mobile.bot.protocol.WsBotClient
import dagger.hilt.android.scopes.ViewModelScoped
import javax.inject.Inject
import kotlinx.coroutines.withTimeout

/**
 * letta-mobile-w2hx.4: Connection tester no longer resolves a "default
 * agent" to bind to — there is no bound agent anymore. The tester just
 * verifies that the lettabot HTTP API is reachable; the per-agent WS
 * session pool (w2hx.3) opens sessions lazily on first real message, so
 * there is nothing to pre-warm here. A green status check is the new
 * contract for "Connection looks good".
 */
@ViewModelScoped
class ClientModeConnectionTester @Inject constructor() {
    suspend fun test(baseUrl: String, apiKey: String?): Result<Unit> = runCatching {
        withTimeout(10_000) {
            WsBotClient(baseUrl = baseUrl, apiKey = apiKey?.takeIf { it.isNotBlank() }).use { client ->
                client.getStatus()
            }
        }
    }.map { Unit }
}
