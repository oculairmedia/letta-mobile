package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.isIrohBackendUrl
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.timeline.TimelineTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

interface ChatGateway : TimelineTransport {
    suspend fun listConversations(limit: Int = DEFAULT_CONVERSATION_LIMIT, archiveStatus: String? = null): List<Conversation>
    suspend fun getConversation(conversationId: String): Conversation
    suspend fun deleteConversation(conversationId: String) {
        throw UnsupportedOperationException("deleteConversation is not supported by this gateway")
    }

    companion object {
        const val DEFAULT_CONVERSATION_LIMIT = 40
    }
}

/**
 * Management operations beyond the core [ChatGateway] contract (agent/
 * conversation creation, model catalog, per-conversation overrides).
 * Desktop reaches these through an interface check on its gateway, so any
 * transport (HTTP, Iroh admin_rpc) can opt in without the controller
 * depending on a concrete gateway class (letta-mobile-yh92w).
 */
interface ChatGatewayExtras {
    suspend fun createConversation(agentId: String, summary: String? = null): Conversation
    suspend fun createAgent(params: AgentCreateParams): Agent
    suspend fun listLlmModels(): List<LlmModel>
    suspend fun setConversationModel(conversationId: String, model: String): Conversation
    suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation
}

/**
 * letta-mobile-wxy4s: gateways that can report their underlying transport's
 * connection state, so a UI controller can surface a drop and auto-recover after
 * the redial instead of silently rendering cached data.
 *
 * Probed the same way as [ChatGatewayExtras] / ApprovalSubmittingGateway: the
 * controller casts its gateway to this interface and, when present, collects
 * [connectionState]. Gateways without a live transport simply don't implement it.
 */
interface ConnectionStatusGateway {
    val connectionState: kotlinx.coroutines.flow.StateFlow<com.letta.mobile.data.transport.ChannelTransportState>
}

interface ChatSessionGraph<out Repositories : SessionRepositoryGraph> {
    val repositories: Repositories
    val gateway: ChatGateway

    fun close()
}

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
        val restored = restoreParkedRemoteBackend(config)
        // A LOCAL config's blank serverUrl must stay blank rather than briefly
        // adopting `fallback`'s (generic, usually remote) URL: that
        // intermediate value would otherwise get parked by
        // migrateStaleLocalServerUrl below, clobbering a real parked remote
        // backend with a placeholder nobody asked to save.
        val serverUrl = if (restored.mode == LettaConfig.Mode.LOCAL) {
            restored.serverUrl.trim()
        } else {
            restored.serverUrl.trim().ifBlank { fallback.serverUrl.trim() }
        }
        val candidate = restored.copy(
            id = restored.id.trim().ifBlank { stableConfigId(generatedIdPrefix, serverUrl) },
            serverUrl = serverUrl,
            accessToken = restored.accessToken?.trim()?.takeIf { it.isNotBlank() },
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
        // letta-mobile-hhp6r: park the remote details being blanked instead of
        // discarding them, so a later switch back to a remote Mode can restore
        // them via [restoreParkedRemoteBackend] rather than forcing re-entry.
        return config.copy(
            serverUrl = "",
            parkedServerUrl = config.serverUrl.trim(),
            parkedAccessToken = config.accessToken?.trim()?.takeIf { it.isNotBlank() }
                ?: config.parkedAccessToken,
        )
    }

    /**
     * letta-mobile-hhp6r: the inverse of the parking done in
     * [migrateStaleLocalServerUrl]. When a config is switching to (or already
     * sitting in) a non-LOCAL [LettaConfig.Mode] with no `serverUrl` typed in,
     * restore the last parked remote backend instead of falling through to
     * [normalize]'s generic `fallback` (which would otherwise silently swap in
     * the default self-hosted URL and drop the user's real backend and token).
     *
     * A config that still has its own `serverUrl` is left untouched — parked
     * data only fills a gap, it never overwrites an explicit value.
     */
    fun restoreParkedRemoteBackend(config: LettaConfig): LettaConfig {
        if (config.mode == LettaConfig.Mode.LOCAL) return config
        if (config.serverUrl.isNotBlank()) return config
        val parkedUrl = config.parkedServerUrl?.trim()
        if (parkedUrl.isNullOrBlank()) return config
        return config.copy(
            serverUrl = parkedUrl,
            accessToken = config.accessToken?.takeIf { it.isNotBlank() } ?: config.parkedAccessToken,
        )
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
