package com.letta.mobile.desktop.chat

import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeLifecycle
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * config's Remote host address is used.
 */
data class DesktopAppServerRuntimeConfig(
    val serverUrl: String? = null,
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
        localRuntime: DesktopLocalRuntimeLifecycle = DesktopLocalRuntimeHost,
    ): DesktopChatGateway {
        val (resolvedConfig, lease) = resolveRuntime(config, appServerConfig, localRuntime)
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
        localRuntime: DesktopLocalRuntimeLifecycle,
    ): Pair<DesktopAppServerRuntimeConfig, DesktopLocalRuntimeLease?> {
        appServerConfig.serverUrl?.let { return appServerConfig to null }
        if (config.mode == LettaConfig.Mode.LOCAL) {
            val lease = withContext(Dispatchers.IO) { localRuntime.acquire() }
            return DesktopAppServerRuntimeConfig(serverUrl = lease.serverUrl) to lease
        }
        return DesktopAppServerRuntimeConfig(serverUrl = config.serverUrl.trim().takeIf { it.isNotBlank() }) to null
    }

    fun defaultFactory(permissionModeSettings: PermissionModeSettings? = null): DesktopAppServerChatGatewayFactory =
        DesktopAppServerChatGatewayBuilder(permissionModeSettings = permissionModeSettings)
}

internal suspend fun createDefaultDesktopChatGateway(
    config: LettaConfig,
    appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig.fromProcess(),
    appServerGatewayFactory: DesktopAppServerChatGatewayFactory = defaultDesktopAppServerGatewayFactory(),
    localRuntime: DesktopLocalRuntimeLifecycle = DesktopLocalRuntimeHost,
): DesktopChatGateway = DesktopAppServerChatGateways.createDefault(config, appServerConfig, appServerGatewayFactory, localRuntime)

internal fun defaultDesktopAppServerGatewayFactory(
    permissionModeSettings: PermissionModeSettings? = null,
): DesktopAppServerChatGatewayFactory =
    DesktopAppServerChatGateways.defaultFactory(permissionModeSettings)
