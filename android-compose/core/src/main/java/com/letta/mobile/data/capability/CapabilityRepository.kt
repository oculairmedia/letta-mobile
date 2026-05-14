package com.letta.mobile.data.capability

import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * letta-mobile-r52v / letta-mobile-2ixd: tracks which optional endpoints
 * the currently-connected backend supports, so the UI can omit affordances
 * that have nothing to call.
 *
 * Currently exposes one capability: projects. The probe is the
 * [ProjectApi.probeAvailability] helper, fired once per active-config
 * identity. Result is cached in [_projectsSupported] until the next
 * config change. Defaults to `true` (assume supported) so the feature
 * stays visible while a probe is in flight or while the backend is
 * unreachable — better to show a broken page than to hide a working one
 * behind a flaky probe.
 *
 * Future capability flags should layer onto the same observer in
 * [observeConfig] rather than spawning parallel one-off jobs; that keeps
 * the probe order deterministic and avoids duplicating the
 * config-change wiring.
 */
@Singleton
class CapabilityRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val projectApi: ProjectApi,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _projectsSupported = MutableStateFlow(true)
    val projectsSupported: StateFlow<Boolean> = _projectsSupported.asStateFlow()

    private var lastProbedConfigId: String? = null

    init {
        observeConfig()
    }

    private fun observeConfig() {
        scope.launch {
            settingsRepository.activeConfig
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.id == new.id }
                .collect { config ->
                    if (config.id == lastProbedConfigId) return@collect
                    lastProbedConfigId = config.id
                    probeProjects(config.id)
                }
        }
    }

    private suspend fun probeProjects(configId: String) {
        Telemetry.event(
            "Capability", "probe.attempted",
            "capability" to "projects",
            "configId" to configId,
        )
        val supported = projectApi.probeAvailability()
        _projectsSupported.value = supported
        Telemetry.event(
            "Capability", "probe.result",
            "capability" to "projects",
            "configId" to configId,
            "supported" to supported,
        )
    }
}
