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

    fun initialize() {
        if (initialized) return
        initialized = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
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

    /**
     * Ensure the Client Mode gateway transport is up.
     *
     * letta-mobile-w2hx.4: previously returned a bound agent ID. The
     * transport no longer binds to an agent — the caller supplies the
     * target agent per-message via `BotChatRequest.agentId`. This now
     * just guarantees that the transport session exists and is healthy.
     */
    suspend fun ensureReady() {
        initialize()
        reconcile(forceForeground = true)
        check(botGateway.getSession(CLIENT_MODE_CONFIG_ID) != null) {
            "Client Mode gateway session is unavailable"
        }
    }

    suspend fun restartSession() {
        initialize()
        stopGateway()
        reconcile(forceForeground = true)
        check(botGateway.getSession(CLIENT_MODE_CONFIG_ID) != null) {
            "Client Mode gateway session is unavailable"
        }
    }

    private suspend fun reconcile(forceForeground: Boolean) {
        mutex.withLock {
            val enabled = settingsRepository.observeClientModeEnabled().first()
            val baseUrl = settingsRepository.observeClientModeBaseUrl().first().trim()
            val apiKey = settingsRepository.getClientModeApiKey()?.trim().orEmpty()
            val shouldRun = enabled && baseUrl.isNotBlank() && (appInForeground || forceForeground)

            if (!shouldRun) {
                stopGatewayLocked()
                return
            }

            // letta-mobile-w2hx.4: no agent resolution here. The
            // transport is agent-agnostic — chats pass their own agent
            // ID per-message. Saves a /status round-trip on every
            // reconcile and removes the layer violation.
            val config = BotConfig(
                id = CLIENT_MODE_CONFIG_ID,
                displayName = "Client Mode",
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
            )
                .joinToString(separator = "|")

            if (activeConfigSignature == signature &&
                botGateway.status.value == GatewayStatus.RUNNING &&
                botGateway.getSession(config.id) != null
            ) {
                return
            }

            stopGatewayLocked()
            botGateway.start(listOf(config))
            activeConfigSignature = if (botGateway.getSession(config.id) != null) signature else null
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
