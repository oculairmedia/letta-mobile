package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.isIrohBackendUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface BackendConfigStore {
    val activeConfig: StateFlow<LettaConfig?>

    suspend fun loadActiveConfig(): LettaConfig?

    suspend fun saveActiveConfig(config: LettaConfig)

    suspend fun recentBackendUrls(): List<String> = emptyList()
}

interface SecureTokenStore {
    fun observeHasToken(): Flow<Boolean>

    suspend fun loadToken(): String?

    suspend fun saveToken(token: String?)

    suspend fun clearToken() {
        saveToken(null)
    }
}

object BackendConfigPolicy {
    /**
     * Network-scheme prefixes that never belong on a `Mode.LOCAL` config's
     * `serverUrl`. Every real LOCAL config either leaves `serverUrl` blank or
     * uses an opaque local placeholder (e.g. Android's
     * `local-lettacode://device`) — none of those start with a network
     * scheme, so any of these prefixes on a LOCAL config is leftover state
     * from a prior remote session, not a legitimate value.
     */
    private val REMOTE_URL_PREFIXES = listOf("iroh://", "http://", "https://", "ws://", "wss://")

    fun normalize(
        config: LettaConfig,
        fallback: LettaConfig,
        generatedIdPrefix: String,
    ): LettaConfig {
        val serverUrl = config.serverUrl.trim().ifBlank { fallback.serverUrl.trim() }
        val candidate = config.copy(
            id = config.id.trim().ifBlank { stableConfigId(generatedIdPrefix, serverUrl) },
            serverUrl = serverUrl,
            accessToken = config.accessToken?.trim()?.takeIf { it.isNotBlank() },
        )
        return migrateStaleLocalServerUrl(candidate)
    }

    /**
     * letta-mobile-9v9nu: mode-authoritative migration for configs persisted
     * before `Mode.LOCAL` became authoritative over `serverUrl` (see
     * [com.letta.mobile.data.model.BackendKind.LOCAL_RUNTIME]'s KDoc). A
     * config can end up with `mode == LOCAL` and a leftover remote
     * `serverUrl` — most commonly an `iroh://` ticket kept from a prior
     * remote session — after which URL-sniffing selectors (Iroh transport
     * binding, subagent side-channel, admin HTTP APIs) silently route to the
     * stale remote backend even though the UI and stored mode both say
     * "Local runtime".
     *
     * Applied on every load (desktop) and every save ([normalize]) so a
     * config already stuck in this state self-heals without the user having
     * to hand-edit their settings file. Every other mode, and LOCAL configs
     * that already have a blank or local-placeholder `serverUrl`, pass
     * through unchanged.
     */
    fun migrateStaleLocalServerUrl(config: LettaConfig): LettaConfig {
        if (config.mode != LettaConfig.Mode.LOCAL) return config
        if (!hasRemoteServerUrl(config)) return config
        return config.copy(serverUrl = "")
    }

    private fun hasRemoteServerUrl(config: LettaConfig): Boolean {
        val url = config.serverUrl.trim()
        if (url.isBlank()) return false
        if (isIrohBackendUrl(url)) return true
        val normalized = url.lowercase()
        return REMOTE_URL_PREFIXES.any { normalized.startsWith(it) }
    }

    fun stableConfigId(prefix: String, serverUrl: String): String =
        "$prefix-${stableLowercaseHash(serverUrl.trim())}"

    private fun stableLowercaseHash(value: String): String {
        var hash = 0
        value.lowercase().forEach { char ->
            hash = hash * 31 + char.code
        }
        return hash.toUInt().toString(radix = 16)
    }
}

class BackendConfigSecureTokenStore(
    private val configStore: BackendConfigStore,
) : SecureTokenStore {
    override fun observeHasToken(): Flow<Boolean> =
        configStore.activeConfig
            .map { it?.accessToken?.isNotBlank() == true }
            .distinctUntilChanged()

    override suspend fun loadToken(): String? =
        configStore.loadActiveConfig()?.accessToken?.trim()?.takeIf { it.isNotBlank() }

    override suspend fun saveToken(token: String?) {
        val current = configStore.loadActiveConfig()
        if (current == null) {
            require(token.isNullOrBlank()) { "Cannot save token without an active backend config" }
            return
        }
        configStore.saveActiveConfig(
            current.copy(accessToken = token?.trim()?.takeIf { it.isNotBlank() }),
        )
    }
}
