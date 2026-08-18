package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeHost
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeLifecycle
import com.letta.mobile.desktop.runtime.DesktopLocalRuntimeLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop insertion contract for a future App Server-backed chat path.
 *
 * This is intentionally disabled by default. The existing REST/SSE desktop
 * gateway remains authoritative for normal desktop chat until a shared
 * App Server gateway can satisfy the same chat contract.
 */
fun interface DesktopAppServerChatGatewayFactory {
    suspend fun create(
        lettaConfig: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig,
    ): DesktopChatGateway
}

data class DesktopAppServerRuntimeConfig(
    val enabled: Boolean = false,
    val serverUrl: String? = null,
) {
    companion object {
        const val ENABLED_PROPERTY = "letta.desktop.appServerChat.enabled"
        const val ENABLED_ENV = "LETTA_DESKTOP_APP_SERVER_CHAT"
        const val SERVER_URL_PROPERTY = "letta.desktop.appServerChat.url"
        const val SERVER_URL_ENV = "LETTA_DESKTOP_APP_SERVER_URL"

        fun fromProcess(): DesktopAppServerRuntimeConfig =
            DesktopAppServerRuntimeConfig(
                enabled = readEnabledFlag(),
                serverUrl = readServerUrl(),
            )

        private fun readEnabledFlag(): Boolean {
            val raw = System.getProperty(ENABLED_PROPERTY) ?: System.getenv(ENABLED_ENV)
            val normalized = raw?.trim()?.lowercase() ?: return false
            return normalized in setOf("1", "true", "yes", "on")
        }

        private fun readServerUrl(): String? {
            val raw = System.getProperty(SERVER_URL_PROPERTY) ?: System.getenv(SERVER_URL_ENV)
            return raw?.trim()?.takeIf { it.isNotBlank() }
        }
    }
}

class DesktopAppServerClientUnavailableException :
    IllegalStateException(
        "Desktop App Server chat is enabled, but no shared App Server gateway is linked. " +
            "Disable ${DesktopAppServerRuntimeConfig.ENABLED_PROPERTY} or provide a " +
            "DesktopAppServerChatGatewayFactory backed by the shared App Server client.",
    )

internal object DesktopAppServerChatGateways {
    suspend fun createDefault(
        config: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig.fromProcess(),
        appServerGatewayFactory: DesktopAppServerChatGatewayFactory? = defaultFactory(),
        localRuntime: DesktopLocalRuntimeLifecycle = DesktopLocalRuntimeHost,
    ): DesktopChatGateway {
        val (resolvedConfig, lease) = resolveRuntime(config, appServerConfig, localRuntime)
        return try {
            val gateway = createGateway(config, resolvedConfig, appServerGatewayFactory)
            lease?.let { DesktopRuntimeOwnedChatGateway(gateway, it) } ?: gateway
        } catch (error: Throwable) {
            lease?.close()
            throw error
        }
    }

    private suspend fun resolveRuntime(
        config: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig,
        localRuntime: DesktopLocalRuntimeLifecycle,
    ): Pair<DesktopAppServerRuntimeConfig, DesktopLocalRuntimeLease?> {
        if (config.mode == LettaConfig.Mode.LOCAL && appServerConfig.serverUrl == null) {
            val lease = withContext(Dispatchers.IO) { localRuntime.acquire() }
            return DesktopAppServerRuntimeConfig(enabled = true, serverUrl = lease.serverUrl) to lease
        }
        return appServerConfig to null
    }

    private suspend fun createGateway(
        config: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig,
        appServerGatewayFactory: DesktopAppServerChatGatewayFactory?,
    ): DesktopChatGateway {
        if (!appServerConfig.enabled) {
            return DesktopLettaHttpChatGateway(config)
        }
        return appServerGatewayFactory?.create(config, appServerConfig)
            ?: throw DesktopAppServerClientUnavailableException()
    }

    fun defaultFactory(): DesktopAppServerChatGatewayFactory? {
        return try {
            DesktopAppServerControllerGatewayFactory()
        } catch (_: Throwable) {
            null
        }
    }
}

internal suspend fun createDefaultDesktopChatGateway(
    config: LettaConfig,
    appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig.fromProcess(),
    appServerGatewayFactory: DesktopAppServerChatGatewayFactory? = defaultDesktopAppServerGatewayFactory(),
    localRuntime: DesktopLocalRuntimeLifecycle = DesktopLocalRuntimeHost,
): DesktopChatGateway = DesktopAppServerChatGateways.createDefault(config, appServerConfig, appServerGatewayFactory, localRuntime)

internal fun defaultDesktopAppServerGatewayFactory(): DesktopAppServerChatGatewayFactory? =
    DesktopAppServerChatGateways.defaultFactory()

