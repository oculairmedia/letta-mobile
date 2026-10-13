package com.letta.mobile.data.health

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.transport.appserver.AppServerProbe
import com.letta.mobile.util.Telemetry
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Long-lived scope ServerHealthRepository uses for its config-observer
 * coroutine: the process lifecycle's scope (the repository is an app-wide
 * singleton), with the work itself on [Dispatchers.IO]; tests can substitute
 * a [kotlinx.coroutines.test.TestScope] via the primary constructor (see
 * letta-mobile-0dnn.7).
 */
internal fun defaultServerHealthScope(): CoroutineScope = ProcessLifecycleOwner.get().lifecycleScope

/**
 * letta-mobile-qmxn: the online dots of the backend pickers ([BackendSwitcherSheet] /
 * [ConfigListScreen]); an OFFLINE row refuses the tap with its shake-and-flash.
 *
 * letta-mobile-ckmub.11: the Android binding of the shared [ServerHealthTracker]. Each saved
 * backend is asked by the shared [ServerHealthProbe]: an App Server's `/app-server-info` (the Letta
 * REST `/v1/health/` route is gone with that backend), never a dial of an `iroh://` host.
 */
@Singleton
class ServerHealthRepository private constructor(
    tracker: ServerHealthTracker,
) : IServerHealthRepository by tracker {
    constructor(settingsRepository: SettingsRepository, scope: CoroutineScope) : this(
        ServerHealthTracker(
            configs = settingsRepository.configs,
            probe = ServerHealthProbe(appServerReachability()),
            scope = scope,
            context = Dispatchers.IO,
            listener = { config, health ->
                Telemetry.event("Health", "probe.result", "configId" to config.id, "result" to health.name)
            },
        ),
    )

    /** Hilt-friendly constructor — uses [defaultServerHealthScope]. */
    @Inject
    constructor(settingsRepository: SettingsRepository) : this(settingsRepository, defaultServerHealthScope())
}

private const val PROBE_TIMEOUT_MS = 3_000L

/** The shared App Server probe over a short-timeout client that classifies every status itself. */
private fun appServerReachability(): AppServerReachability {
    val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = PROBE_TIMEOUT_MS
                connectTimeoutMillis = PROBE_TIMEOUT_MS
                socketTimeoutMillis = PROBE_TIMEOUT_MS
            }
            expectSuccess = false
        }
    }
    val probe by lazy { AppServerProbe(client, timeout = PROBE_TIMEOUT_MS.milliseconds) }
    return AppServerReachability { baseUrl, token -> probe.probe(baseUrl, token) }
}
