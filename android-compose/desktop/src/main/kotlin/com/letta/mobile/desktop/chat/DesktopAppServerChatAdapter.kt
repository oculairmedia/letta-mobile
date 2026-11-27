package com.letta.mobile.desktop.chat

import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.runtime.AgentDefaultsRepository
import com.letta.mobile.data.runtime.PermissionModeSettings
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.transport.iroh.IrohChannelTransport

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
 * or `LETTA_DESKTOP_APP_SERVER_URL`); without it the config's Remote host address is used. [bearerToken],
 * when set, authenticates the dial in place of the config's access token.
 */
data class DesktopAppServerRuntimeConfig(
    val serverUrl: String? = null,
    val bearerToken: String? = null,
) {
    companion object {
        const val SERVER_URL_PROPERTY = "letta.desktop.appServerChat.url"
        const val SERVER_URL_ENV = "LETTA_DESKTOP_APP_SERVER_URL"

        /** Developer setting that dials a ws:// host directly: `-Dletta.desktop.directAppServer=true` or its env twin. */
        const val DIRECT_APP_SERVER_PROPERTY = "letta.desktop.directAppServer"
        const val DIRECT_APP_SERVER_ENV = "LETTA_DESKTOP_DIRECT_APP_SERVER"

        fun fromProcess(): DesktopAppServerRuntimeConfig =
            DesktopAppServerRuntimeConfig(serverUrl = readServerUrl())

        /** Whether the developer setting for a directly dialled ws:// App Server is on in this process. */
        fun directAppServerAllowed(): Boolean =
            (System.getProperty(DIRECT_APP_SERVER_PROPERTY) ?: System.getenv(DIRECT_APP_SERVER_ENV)).equals("true", ignoreCase = true)

        private fun readServerUrl(): String? {
            val raw = System.getProperty(SERVER_URL_PROPERTY) ?: System.getenv(SERVER_URL_ENV)
            return raw?.trim()?.takeIf { it.isNotBlank() }
        }
    }
}

internal object DesktopAppServerChatGateways {
    /** The gateway for an App Server dialled directly; the bundled runtime is reached through the desktop's node, never here. */
    suspend fun createDefault(
        config: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig.fromProcess(),
        appServerGatewayFactory: DesktopAppServerChatGatewayFactory = defaultFactory(),
        directAppServerAllowed: Boolean = DesktopAppServerRuntimeConfig.directAppServerAllowed(),
    ): DesktopChatGateway =
        appServerGatewayFactory.create(config, resolveRuntime(config, appServerConfig, directAppServerAllowed))

    /** The override, else the config's own address (a ws:// host needs the developer setting); never a LOCAL config's. */
    private fun resolveRuntime(
        config: LettaConfig,
        appServerConfig: DesktopAppServerRuntimeConfig,
        directAppServerAllowed: Boolean,
    ): DesktopAppServerRuntimeConfig {
        appServerConfig.serverUrl?.let { return appServerConfig }
        check(config.mode != LettaConfig.Mode.LOCAL) { LOCAL_RUNTIME_IS_A_NODE }
        val address = config.serverUrl.trim().takeIf { it.isNotBlank() }
        val allowed = address == null || directAppServerAllowed || IrohChannelTransport.isIrohUrl(address)
        check(allowed) { DIRECT_IS_DEVELOPER_ONLY }
        return DesktopAppServerRuntimeConfig(serverUrl = address)
    }

    private const val LOCAL_RUNTIME_IS_A_NODE =
        "The bundled runtime is reached through the desktop's own node, not dialled directly."
    private const val DIRECT_IS_DEVELOPER_ONLY =
        "A ws:// App Server address is a developer setting. Use an iroh:// node or the bundled runtime, or start the " +
            "desktop with -D${DesktopAppServerRuntimeConfig.DIRECT_APP_SERVER_PROPERTY}=true."

    fun defaultFactory(
        permissionModeSettings: PermissionModeSettings? = null,
        agentDefaults: AgentDefaultsRepository? = null,
        canvasSessions: CanvasSessionRegistry = CanvasSessionRegistry(),
    ): DesktopAppServerChatGatewayFactory =
        DesktopAppServerChatGatewayBuilder(
            canvasSessions = canvasSessions,
            permissionModeSettings = permissionModeSettings,
            agentDefaults = agentDefaults,
        )
}

internal suspend fun createDefaultDesktopChatGateway(
    config: LettaConfig,
    appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig.fromProcess(),
    appServerGatewayFactory: DesktopAppServerChatGatewayFactory = defaultDesktopAppServerGatewayFactory(),
    directAppServerAllowed: Boolean = DesktopAppServerRuntimeConfig.directAppServerAllowed(),
): DesktopChatGateway =
    DesktopAppServerChatGateways.createDefault(config, appServerConfig, appServerGatewayFactory, directAppServerAllowed)

internal fun defaultDesktopAppServerGatewayFactory(
    permissionModeSettings: PermissionModeSettings? = null,
    agentDefaults: AgentDefaultsRepository? = null,
    canvasSessions: CanvasSessionRegistry = CanvasSessionRegistry(),
): DesktopAppServerChatGatewayFactory =
    DesktopAppServerChatGateways.defaultFactory(permissionModeSettings, agentDefaults, canvasSessions)
