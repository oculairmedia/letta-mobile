package com.letta.mobile.data.health

import com.letta.mobile.data.api.LettaApiClient
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.util.Telemetry
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects whether the active backend is the letta-code admin shim.
 *
 * The chat send path must only use the mobile WebSocket channel for shim
 * backends. Vanilla Letta servers and cloud keep the REST path. The shim
 * advertises itself on `/v1/health` with `backend = "letta-code-local"`;
 * results are cached per active config id so a chat screen can make a cheap
 * routing decision after the first probe.
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

    val activeIsShimBackend: StateFlow<Boolean> = combine(
        activeConfig,
        states,
    ) { config, states ->
        config?.let { states[it.id] } ?: false
    }.stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun refreshActive(): Boolean {
        val config = activeConfig.value ?: return false
        return refresh(config)
    }

    suspend fun refresh(config: LettaConfig): Boolean = probeMutex.withLock {
        _states.value[config.id]?.let { return@withLock it }
        if (config.mode == LettaConfig.Mode.LOCAL) {
            _states.value = _states.value + (config.id to false)
            return@withLock false
        }

        val detected = runCatching {
            val response = apiClient.getClient().get("v1/health")
            if (response.status.value !in 200..299) {
                false
            } else {
                response.body<ShimHealthPayload>().isShimBackend()
            }
        }.getOrElse { false }

        _states.value = _states.value + (config.id to detected)
        Telemetry.event(
            "Backend", "shim_probe.result",
            "configId" to config.id,
            "serverUrl" to config.serverUrl,
            "isShim" to detected,
        )
        detected
    }

    fun cachedActiveIsShimBackend(): Boolean = activeIsShimBackend.value
}

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
