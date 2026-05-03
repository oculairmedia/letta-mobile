package com.letta.mobile.clientmode

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.letta.mobile.bot.config.BotConfig
import com.letta.mobile.bot.core.BotGateway
import com.letta.mobile.bot.core.GatewayStatus
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.util.Telemetry
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

    /**
     * letta-mobile-etc1: previously the controller tore down the WS bot
     * gateway in [onStop] (i.e. whenever the screen turned off / app left
     * the foreground). That cancelled any in-flight Client Mode run
     * mid-stream because the per-agent WS session was destroyed under
     * the active `streamMessage` collector — the user lost the run
     * simply by locking their phone.
     *
     * The gateway is now treated as a process-scoped transport whose
     * lifecycle follows the Client Mode settings (enabled + base URL),
     * NOT the UI lifecycle. Process lifetime is already extended by
     * `ChatPushService` (foreground service), so leaving the WS open
     * across screen-off is consistent with how SSE timeline subscribers
     * behave (`TimelineSyncLoop.runStreamSubscriber`, see
     * docs/architecture/screen-off-streaming-findings.md).
     */
    private val stopGatewayOnAppBackground: Boolean = false

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
        // letta-mobile-etc1: do NOT tear down the WS bot gateway here.
        // Stopping the gateway on app background cancels any in-flight
        // Client Mode run because the WS session is destroyed under the
        // active streamMessage collector. The gateway is now
        // process-scoped and only stops when Client Mode is disabled in
        // settings (handled by the settings observer in initialize()).
        Telemetry.event(
            "ClientModeController", "appBackgrounded",
            "gatewayKeptAlive" to (!stopGatewayOnAppBackground).toString(),
        )
        if (stopGatewayOnAppBackground) {
            scope.launch { stopGateway() }
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
            // letta-mobile-etc1: gateway lifecycle is settings-driven, not
            // UI-foreground-driven. Once Client Mode is enabled + has a
            // base URL, the WS transport stays up across screen-off so
            // long-running agent runs are not torn down mid-stream.
            // `forceForeground` is preserved so callers like
            // `ensureReady()` / `restartSession()` can demand the
            // transport even before the first lifecycle tick.
            val shouldRun = enabled && baseUrl.isNotBlank() && (
                !stopGatewayOnAppBackground || appInForeground || forceForeground
            )

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
