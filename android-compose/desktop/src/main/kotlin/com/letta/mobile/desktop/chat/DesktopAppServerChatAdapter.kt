package com.letta.mobile.desktop.chat

import com.letta.mobile.data.runtime.AgentDefaultsRepository
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.runtime.host.LocalAppServerLease
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost

/**
 * Builds the desktop's App Server chat gateway for a backend address.
 *
 * letta-mobile-ckmub.4: this is the desktop's one backend seam. Every config (the bundled local
 * runtime or a remote App Server over ws:// / iroh://) is served by [DesktopAppServerChatGateway];
 * there is no Letta REST gateway. A future backend family (e.g. a cloud backend) plugs in as another
 * factory chosen from the config's `BackendKind` in [DesktopAppServerChatGateways.createDefault],
 * without touching the shared shell.
 */
fun interface DesktopAppServerChatGatewayFactory {
    suspend fun create(
        lettaConfig: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig,
    ): DesktopAppServerChatGateway
}

/**
 * Where the App Server is. [serverUrl] is a developer override (`-Dletta.desktop.appServerChat.url`
 * or `LETTA_DESKTOP_APP_SERVER_URL`); without it the bundled runtime's address (LOCAL) or the
 * config's Remote host address is used. [bearerToken], when set, authenticates the dial in place of
 * the config's access token (the bundled runtime's capability token, letta-mobile-by4vm.110.4).
 */
data class DesktopAppServerRuntimeConfig(
    val serverUrl: String? = null,
    val bearerToken: String? = null,
) {
    companion object {
        const val SERVER_URL_PROPERTY = "letta.desktop.appServerChat.url"
        const val SERVER_URL_ENV = "LETTA_DESKTOP_APP_SERVER_URL"

        fun fromProcess(): DesktopAppServerRuntimeConfig =
            DesktopAppServerRuntimeConfig(serverUrl = readServerUrl())

        private fun readServerUrl(): String? {
            val raw = System.getProperty(SERVER_URL_PROPERTY) ?: System.getenv(SERVER_URL_ENV)
            return raw?.trim()?.takeIf { it.isNotBlank() }
        }
    }
}

internal object DesktopAppServerChatGateways {
    suspend fun createDefault(
        config: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig.fromProcess(),
        appServerGatewayFactory: DesktopAppServerChatGatewayFactory = defaultFactory(),
        acquireLocalRuntime: suspend () -> LocalAppServerLease = DesktopLocalRuntimeHost.appServer::acquire,
    ): DesktopChatGateway {
        val (resolvedConfig, lease) = resolveRuntime(config, appServerConfig, acquireLocalRuntime)
        return try {
            val gateway = appServerGatewayFactory.create(config, resolvedConfig)
            lease?.let(gateway::owningRuntime) ?: gateway
        } catch (error: Throwable) {
            lease?.close()
            throw error
        }
    }

    /**
     * LOCAL (with no override) starts the bundled runtime and dials it; any other config dials its
     * own address. Mode is authoritative: a LOCAL config's leftover remote address is never used
     * (letta-mobile-9v9nu).
     */
    private suspend fun resolveRuntime(
        config: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig,
        acquireLocalRuntime: suspend () -> LocalAppServerLease,
    ): Pair<DesktopAppServerRuntimeConfig, LocalAppServerLease?> {
        appServerConfig.serverUrl?.let { return appServerConfig to null }
        if (config.mode == LettaConfig.Mode.LOCAL) {
            val lease = acquireLocalRuntime()
            return DesktopAppServerRuntimeConfig(serverUrl = lease.serverUrl, bearerToken = lease.bearerToken) to lease
        }
        return DesktopAppServerRuntimeConfig(serverUrl = config.serverUrl.trim().takeIf { it.isNotBlank() }) to null
    }

    fun defaultFactory(
        permissionModeSettings: PermissionModeSettings? = null,
        agentDefaults: AgentDefaultsRepository? = null,
    ): DesktopAppServerChatGatewayFactory =
        DesktopAppServerChatGatewayBuilder(
            permissionModeSettings = permissionModeSettings,
            agentDefaults = agentDefaults,
        )
}

internal suspend fun createDefaultDesktopChatGateway(
    config: LettaConfig,
    appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig.fromProcess(),
    appServerGatewayFactory: DesktopAppServerChatGatewayFactory = defaultDesktopAppServerGatewayFactory(),
    acquireLocalRuntime: suspend () -> LocalAppServerLease = DesktopLocalRuntimeHost.appServer::acquire,
): DesktopChatGateway =
    DesktopAppServerChatGateways.createDefault(config, appServerConfig, appServerGatewayFactory, acquireLocalRuntime)

internal fun defaultDesktopAppServerGatewayFactory(
    permissionModeSettings: PermissionModeSettings? = null,
    agentDefaults: AgentDefaultsRepository? = null,
): DesktopAppServerChatGatewayFactory =
    DesktopAppServerChatGateways.defaultFactory(permissionModeSettings, agentDefaults)
