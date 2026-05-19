package com.letta.mobile.data.health

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.util.Telemetry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * letta-mobile-qmxn: per-config wake test for the backend pickers.
 *
 * Pings each configured backend with a short timeout and exposes the
 * result as a [Health] flag the UI can render as a green/red dot beside
 * the row in [BackendSwitcherSheet] / [ConfigListScreen]. A row that
 * comes back [Health.OFFLINE] is the picker's signal to refuse the tap
 * (the row's own composable runs the shake-and-flash refusal animation).
 *
 * The probe is intentionally cheap and tolerant: any HTTP response from
 * `/v1/health/` — including 401, 404, 5xx — is treated as ONLINE because
 * the server is at least answering. Only network errors and timeouts
 * (ECONNREFUSED, UnknownHostException, etc.) drop a config to OFFLINE.
 *
 * Re-probing fires (a) automatically whenever the set of configured IDs
 * changes, and (b) on demand via [refreshAll] from picker UIs that want
 * fresh state on open. There is no periodic background poll — the user
 * explicitly opted out of that on letta-mobile-qmxn.
 */
/**
 * Long-lived scope ServerHealthRepository uses for its config-observer
 * coroutine. Defaults to [Dispatchers.IO] + a fresh [SupervisorJob]; tests
 * can substitute a [kotlinx.coroutines.test.TestScope] via the primary
 * constructor (see letta-mobile-0dnn.7).
 */
internal fun defaultServerHealthScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Singleton
class ServerHealthRepository(
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {
    /** Hilt-friendly constructor — uses [defaultServerHealthScope]. */
    @Inject
    constructor(settingsRepository: SettingsRepository) :
        this(settingsRepository, defaultServerHealthScope())

    enum class Health { UNKNOWN, PROBING, ONLINE, OFFLINE }

    private val _states = MutableStateFlow<Map<String, Health>>(emptyMap())
    val states: StateFlow<Map<String, Health>> = _states.asStateFlow()

    private val refreshMutex = Mutex()

    private val probeClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = PROBE_TIMEOUT_MS
                connectTimeoutMillis = PROBE_TIMEOUT_MS
                socketTimeoutMillis = PROBE_TIMEOUT_MS
            }
            expectSuccess = false
        }
    }

    init {
        scope.launch {
            // First probe runs as soon as any configs are visible. Re-probe
            // only when the set of IDs actually changes — purely renaming an
            // entry shouldn't burn another network round-trip.
            settingsRepository.configs
                .map { configs -> configs.map { it.id }.toSet() to configs }
                .distinctUntilChanged { old, new -> old.first == new.first }
                .collect { (_, configs) -> refreshAllInternal(configs) }
        }
    }

    /** Public re-probe trigger for picker UIs. Safe to call from main thread. */
    suspend fun refreshAll() {
        refreshAllInternal(settingsRepository.configs.value)
    }

    private suspend fun refreshAllInternal(configs: List<LettaConfig>) {
        if (configs.isEmpty()) {
            _states.value = emptyMap()
            return
        }
        refreshMutex.withLock {
            // Drop entries for configs that no longer exist; mark all live
            // ones PROBING so the dot shows a transient state instead of
            // flipping straight from a stale ONLINE/OFFLINE.
            val liveIds = configs.map { it.id }.toSet()
            _states.update { current ->
                val pruned = current.filterKeys { it in liveIds }
                pruned + configs.associate { it.id to Health.PROBING }
            }

            coroutineScope {
                configs.map { config ->
                    async { probe(config) }
                }.awaitAll()
            }
        }
    }

    private suspend fun probe(config: LettaConfig) {
        val baseUrl = config.serverUrl.trim().trimEnd('/')
        val target = "$baseUrl/v1/health/"
        // Bare try/catch (not runCatching) so a CancellationException
        // — e.g. parent scope cancelled while a probe is mid-flight —
        // propagates up instead of being misclassified as OFFLINE.
        val outcome: Result<io.ktor.client.statement.HttpResponse> = try {
            Result.success(
                probeClient.get(target) {
                    val token = config.accessToken?.trim()
                    if (!token.isNullOrEmpty()) {
                        header("Authorization", "Bearer $token")
                    }
                }
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Result.failure(t)
        }
        val health = if (outcome.isSuccess) Health.ONLINE else Health.OFFLINE
        _states.update { it + (config.id to health) }
        Telemetry.event(
            "Health", "probe.result",
            "configId" to config.id,
            "url" to baseUrl,
            "status" to (outcome.getOrNull()?.status?.value ?: -1),
            "result" to health.name,
        )
    }

    companion object {
        private const val PROBE_TIMEOUT_MS = 3_000L
    }
}
