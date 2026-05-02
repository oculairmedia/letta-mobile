package com.letta.mobile.clientmode

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.core.GatewayStatus
import com.letta.mobile.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Client Mode session authority.
 *
 * Contract:
 * - The route agent (from AdminChatViewModel navigation state) is the
 *   authoritative owner of a Client Mode chat route.
 * - Existing conversations are only valid when resumed under that same
 *   route agent.
 * - When a route agent is explicitly known, this controller MUST bind the
 *   gateway session to that agent and MUST NOT fall back to a previously
 *   active agent or an arbitrary preferred remote agent.
 * - Preferred-agent resolution is allowed only when the caller does not know
 *   which agent owns the route.
 */
@Singleton
class ClientModeController @Inject constructor(
    private val botGateway: BotGateway,
    private val settingsRepository: SettingsRepository,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    private var appInForeground = false

    @Volatile
    private var activeConfigSignature: String? = null

    @Volatile
    private var activeRemoteAgentId: String? = null

    fun initialize() {
        if (initialized) return
        initialized = true
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }
        scope.launch {
            combine(
                settingsRepository.observeClientModeEnabled(),
                settingsRepository.observeClientModeBaseUrl(),
            ) { enabled, baseUrl -> enabled to baseUrl.trim() }
                .collect {
                    reconcile(forceForeground = false)
                }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        appInForeground = true
        scope.launch {
            reconcile(forceForeground = true)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        appInForeground = false
        scope.launch {
            stopGateway()
        }
    }

    suspend fun ensureReady(routeAgentId: String? = null): String {
        initialize()
        reconcile(forceForeground = true, routeAgentId = routeAgentId)
        val remoteAgentId = requireNotNull(activeRemoteAgentId) {
            "Client Mode gateway session is unavailable"
        }
        check(botGateway.getSession(remoteAgentId) != null) {
            "Client Mode gateway session is unavailable"
        }
        return remoteAgentId
    }

    suspend fun restartSession(routeAgentId: String? = null): String {
        initialize()
        stopGateway()
        reconcile(forceForeground = true, routeAgentId = routeAgentId)
        val remoteAgentId = requireNotNull(activeRemoteAgentId) {
            "Client Mode gateway session is unavailable"
        }
        check(botGateway.getSession(remoteAgentId) != null) {
            "Client Mode gateway session is unavailable"
        }
        return remoteAgentId
    }

    private suspend fun reconcile(forceForeground: Boolean, routeAgentId: String? = null) {
        mutex.withLock {
            val enabled = settingsRepository.observeClientModeEnabled().first()
            val baseUrl = settingsRepository.observeClientModeBaseUrl().first().trim()
            val apiKey = settingsRepository.getClientModeApiKey()?.trim().orEmpty()
            val shouldRun = enabled && baseUrl.isNotBlank() && (appInForeground || forceForeground)

            if (!shouldRun) {
                stopGatewayLocked()
                return
            }

            val explicitRouteAgentId = routeAgentId?.trim()?.takeIf { it.isNotEmpty() }
            val resolvedAgentId = explicitRouteAgentId ?: activeRemoteAgentId ?: resolveClientModeRemoteAgent(
                baseUrl = baseUrl,
                apiKey = apiKey.ifBlank { null },
            ).id

            val config = BotConfig(
                id = CLIENT_MODE_CONFIG_ID,
                agentId = resolvedAgentId,
                displayName = "$resolvedAgentId Client Mode",
                mode = BotConfig.Mode.REMOTE,
                remoteUrl = baseUrl,
                remoteToken = apiKey.ifBlank { null },
                transport = BotConfig.Transport.WS,
                channels = listOf("letta-mobile"),
                enabled = true,
            )
            val signature = listOf(
                config.remoteUrl.orEmpty(),
                config.remoteToken.orEmpty(),
                config.transport.name,
                config.agentId,
            )
                .joinToString(separator = "|")

            if (activeConfigSignature == signature &&
                botGateway.status.value == GatewayStatus.RUNNING &&
                botGateway.getSession(config.agentId) != null
            ) {
                return
            }

            stopGatewayLocked()
            botGateway.start(listOf(config))
            if (botGateway.getSession(config.agentId) != null) {
                activeConfigSignature = signature
                activeRemoteAgentId = config.agentId
            } else {
                activeConfigSignature = null
                activeRemoteAgentId = null
            }
        }
    }

    private suspend fun stopGateway() {
        mutex.withLock {
            stopGatewayLocked()
        }
    }

    private suspend fun stopGatewayLocked() {
        if (botGateway.status.value != GatewayStatus.STOPPED || botGateway.sessions.value.isNotEmpty()) {
            botGateway.stop()
        }
        activeConfigSignature = null
        activeRemoteAgentId = null
    }

    fun release() {
        if (!initialized) return
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        }
        scope.cancel()
        initialized = false
    }

    companion object {
        private const val CLIENT_MODE_CONFIG_ID = "client-mode-gateway"
    }
}
