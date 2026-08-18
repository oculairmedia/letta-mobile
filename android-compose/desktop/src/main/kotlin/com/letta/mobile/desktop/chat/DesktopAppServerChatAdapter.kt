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

internal suspend fun createDefaultDesktopChatGateway(
    config: LettaConfig,
    appServerConfig: DesktopAppServerRuntimeConfig = DesktopAppServerRuntimeConfig.fromProcess(),
    appServerGatewayFactory: DesktopAppServerChatGatewayFactory? = defaultDesktopAppServerGatewayFactory(),
    localRuntime: DesktopLocalRuntimeLifecycle = DesktopLocalRuntimeHost,
): DesktopChatGateway {
    var runtimeLease: DesktopLocalRuntimeLease? = null
    val resolvedAppServerConfig = when {
        config.mode == LettaConfig.Mode.LOCAL && appServerConfig.serverUrl == null -> {
            val lease = withContext(Dispatchers.IO) { localRuntime.acquire() }
            runtimeLease = lease
            DesktopAppServerRuntimeConfig(enabled = true, serverUrl = lease.serverUrl)
        }
        appServerConfig.enabled -> appServerConfig
        else -> appServerConfig
    }
    try {
        val gateway = if (resolvedAppServerConfig.enabled) {
            appServerGatewayFactory?.create(config, resolvedAppServerConfig)
                ?: throw DesktopAppServerClientUnavailableException()
        } else {
            DesktopLettaHttpChatGateway(config)
        }
        return runtimeLease?.let { DesktopRuntimeOwnedChatGateway(gateway, it) } ?: gateway
    } catch (error: Throwable) {
        runtimeLease?.close()
        throw error
    }
}

private class DesktopRuntimeOwnedChatGateway(
    private val delegate: DesktopChatGateway,
    private val runtimeLease: DesktopLocalRuntimeLease,
) : DesktopChatGateway by delegate,
    DesktopApprovalSubmitter,
    DesktopTurnAborter,
    DesktopWorkingDirectoryController,
    AutoCloseable {
    override suspend fun submitApproval(submission: DesktopApprovalSubmission) {
        (delegate as? DesktopApprovalSubmitter)?.submitApproval(submission)
            ?: error("The local App Server gateway cannot submit approvals")
    }

    override suspend fun abortConversationTurn(conversationId: String): Boolean =
        (delegate as? DesktopTurnAborter)?.abortConversationTurn(conversationId) ?: false

    override suspend fun currentWorkingDirectory(agentId: String, conversationId: String): String? =
        (delegate as? DesktopWorkingDirectoryController)?.currentWorkingDirectory(agentId, conversationId)

    override suspend fun setWorkingDirectory(agentId: String, conversationId: String, path: String): Boolean =
        (delegate as? DesktopWorkingDirectoryController)?.setWorkingDirectory(agentId, conversationId, path) ?: false

    override fun close() {
        try {
            (delegate as? AutoCloseable)?.close()
        } finally {
            runtimeLease.close()
        }
    }
}

/**
 * Creates the default App Server gateway factory for desktop chat.
 *
 * Returns a controller-backed factory that wires the App Server transport,
 * client, and controller stack. The factory is only used when
 * [DesktopAppServerRuntimeConfig.enabled] is true.
 *
 * @return The default factory, or null if controller components are not available
 */
fun defaultDesktopAppServerGatewayFactory(): DesktopAppServerChatGatewayFactory? {
    return try {
        DesktopAppServerControllerGatewayFactory()
    } catch (e: NoClassDefFoundError) {
        // Controller classes not available (stripped build, test scenario, etc.)
        null
    } catch (e: ClassNotFoundException) {
        null
    }
}
