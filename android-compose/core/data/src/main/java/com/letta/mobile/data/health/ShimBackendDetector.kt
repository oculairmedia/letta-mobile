package com.letta.mobile.data.health

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind
import com.letta.mobile.data.model.isIrohBackend
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.backendUrlTelemetryDescriptor
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies the active backend (see [BackendKind]).
 *
 * letta-mobile-lgns8.10.4.1 — this class used to answer a single boolean,
 * `activeIsShimBackend`, and it returned **true for Iroh backends** so that
 * the chat screen would pick the frame-channel send strategy. That conflated
 * two different questions and inverted the routing key: an Iroh client
 * selected the shim-shaped strategy and nothing structurally prevented shim
 * traffic. The two questions are now separate:
 *
 *  - [activeUsesChannelTransport] — "is this backend served by a duplex frame
 *    channel?" (Iroh **or** shim WS). This is what the chat UI wants wherever
 *    it historically asked `isShimBackend`.
 *  - [activeIsShimBackend] — "is this backend genuinely the LettaShim?" True
 *    only for [BackendKind.SHIM_WS].
 *
 * The `/v1/health` probe (`backend = "letta-code-local"`) is the only thing
 * that needs the network, and it must never run for an Iroh config: that probe
 * is itself an HTTP dial at the shim's address.
 */
@Singleton
class ShimBackendDetector internal constructor(
    private val activeConfig: StateFlow<LettaConfig?>,
    private val apiClient: LettaApiClient,
) {
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        apiClient: LettaApiClient,
    ) : this(settingsRepository.activeConfig, apiClient)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val probeMutex = Mutex()
    private val _states = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    val states: StateFlow<Map<String, Boolean>> = _states.asStateFlow()

    /**
     * Classification of [config] with the currently cached probe result.
     * Pure and synchronous — no network, no coroutine.
     */
    private fun kindOf(config: LettaConfig?, probed: Map<String, Boolean> = _states.value): BackendKind =
        config?.backendKind(
            shimDetected = probed[config.id] ?: false,
            forceIroh = config.forcedIroh(),
        ) ?: BackendKind.REST

    val activeBackendKind: StateFlow<BackendKind> = combine(
        activeConfig,
        states,
    ) { config, probed ->
        kindOf(config, probed)
    }.stateIn(scope, SharingStarted.Eagerly, kindOf(activeConfig.value))

    /**
     * True for Iroh **and** shim-WS backends: both are served by an
     * `IChannelTransport`. Chat coordinators that need "does this backend
     * stream frames?" must use this, not [activeIsShimBackend].
     */
    val activeUsesChannelTransport: StateFlow<Boolean> = activeBackendKind
        .map { it.usesChannelTransport }
        .stateIn(scope, SharingStarted.Eagerly, kindOf(activeConfig.value).usesChannelTransport)

    /** True only for a genuine LettaShim backend. Iroh is NOT a shim backend. */
    val activeIsShimBackend: StateFlow<Boolean> = activeBackendKind
        .map { it.isShim }
        .stateIn(scope, SharingStarted.Eagerly, kindOf(activeConfig.value).isShim)

    suspend fun refreshActive(): Boolean {
        val config = activeConfig.value ?: return false
        return refresh(config)
    }

    suspend fun refresh(config: LettaConfig): Boolean = probeMutex.withLock {
        // lgns8.10.4.1: Iroh and local-runtime configs are classified from
        // config truth and are NEVER health-probed. The probe would be an HTTP
        // dial at `config.serverUrl`, which is precisely the shim traffic this
        // bead removes from the Iroh path. `false` here means "not a shim",
        // which is the correct answer for both.
        if (config.isProbeExempt()) {
            _states.value += (config.id to false)
            Telemetry.event(
                "Backend", "shim_probe.skipped",
                "configId" to config.id,
                "kind" to config.backendKind(forceIroh = config.forcedIroh()).name,
            )
            return@withLock false
        }

        _states.value[config.id]?.let { return@withLock it }

        val detected = runCatching {
            val response = apiClient.getClient().get("v1/health")
            if (response.status.value !in 200..299) {
                false
            } else {
                response.body<ShimHealthPayload>().isShimBackend()
            }
        }.getOrElse { false }

        _states.value += (config.id to detected)
        Telemetry.event(
            "Backend", "shim_probe.result",
            "configId" to config.id,
            "serverUrl" to backendUrlTelemetryDescriptor(config.serverUrl),
            "isShim" to detected,
        )
        detected
    }

    // The three cached accessors are computed directly rather than read off the
    // shared StateFlows: those are `stateIn`-ed on a background IO scope, so a
    // caller that reads them immediately after a config change can observe the
    // previous value. Callers use these as the seed for their own `stateIn`, so
    // a stale seed is a visible first-frame misroute.
    fun cachedActiveIsShimBackend(): Boolean = cachedActiveBackendKind().isShim

    fun cachedActiveUsesChannelTransport(): Boolean = cachedActiveBackendKind().usesChannelTransport

    fun cachedActiveBackendKind(): BackendKind = kindOf(activeConfig.value)
}

/**
 * Configs whose kind is decided by config truth alone, so probing them would be
 * pure network cost — and, for an Iroh config, an HTTP dial at the shim's own
 * address, which is exactly what lgns8.10.4.1 removes.
 */
private fun LettaConfig.isProbeExempt(): Boolean =
    mode == LettaConfig.Mode.LOCAL || isIrohBackend() || forcedIroh()

/**
 * lgns8.10.4.1: classification must agree with the transport binding.
 * `SessionGraphFactory` binds `IrohChannelTransport` on
 * [IrohChannelTransport.shouldUseIroh], which is `isIrohUrl(url)` OR the
 * debug-only `DEBUG_FORCE_IROH_URL` override. Keying only on the URL shape left
 * a debug-force build classifying an Iroh session as a shim/REST backend — and
 * then health-probing `http://…:8291` over HTTP.
 */
private fun LettaConfig.forcedIroh(): Boolean =
    IrohChannelTransport.shouldUseIroh(serverUrl)

@Serializable
private data class ShimHealthPayload(
    val version: String? = null,
    val status: String? = null,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("server_started_at") val serverStartedAt: String? = null,
    val backend: String? = null,
)

private fun ShimHealthPayload.isShimBackend(): Boolean =
    backend.equals("letta-code-local", ignoreCase = true) ||
        version.orEmpty().startsWith("shim-", ignoreCase = true)
