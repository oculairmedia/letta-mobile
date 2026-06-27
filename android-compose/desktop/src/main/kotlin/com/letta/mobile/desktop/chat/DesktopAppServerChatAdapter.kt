package com.letta.mobile.desktop.chat

import com.letta.mobile.data.model.LettaConfig

/**
 * Desktop insertion contract for a future App Server-backed chat path.
 *
 * This is intentionally disabled by default. The existing REST/SSE desktop
 * gateway remains authoritative for normal desktop chat until a shared
 * App Server gateway can satisfy the same chat contract.
 */
fun interface DesktopAppServerChatGatewayFactory {
    fun create(
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
                enabled = readFlag(
                    systemValue = System.getProperty(ENABLED_PROPERTY),
                    environmentValue = System.getenv(ENABLED_ENV),
                ),
                serverUrl = System.getProperty(SERVER_URL_PROPERTY)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: System.getenv(SERVER_URL_ENV)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() },
            )

        private fun readFlag(systemValue: String?, environmentValue: String?): Boolean =
            (systemValue ?: environmentValue)
                ?.trim()
                ?.lowercase()
                ?.let { it == "1" || it == "true" || it == "yes" || it == "on" }
                ?: false
    }
}

class DesktopAppServerClientUnavailableException :
    IllegalStateException(
        "Desktop App Server chat is enabled, but no shared App Server gateway is linked. " +
            "Disable ${DesktopAppServerRuntimeConfig.ENABLED_PROPERTY} or provide a " +
            "DesktopAppServerChatGatewayFactory backed by the shared App Server client.",
    )

fun createDefaultDesktopChatGateway(
    config: LettaConfig,
    appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig.fromProcess(),
    appServerGatewayFactory: DesktopAppServerChatGatewayFactory? = null,
): DesktopChatGateway =
    if (appServerConfig.enabled) {
        appServerGatewayFactory?.create(config, appServerConfig)
            ?: throw DesktopAppServerClientUnavailableException()
    } else {
        DesktopLettaHttpChatGateway(config)
    }
