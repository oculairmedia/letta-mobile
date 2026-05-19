package com.letta.mobile.data.capability

import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.repository.ProjectRepository
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
 * config change. Initial value is `true` so the tab stays visible during
 * the very first probe of the session (the user added the config, they
 * almost certainly expect it to work); after that the probe is
 * authoritative and any failure — including network errors against an
 * unreachable backend — flips the flag to false.
 *
 * Future capability flags should layer onto the same observer in
 * [observeConfig] rather than spawning parallel one-off jobs; that keeps
 * the probe order deterministic and avoids duplicating the
 * config-change wiring.
 */
internal fun defaultCapabilityScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@Singleton
class CapabilityRepository(
    private val settingsRepository: SettingsRepository,
    private val projectApi: ProjectApi,
    private val projectRepository: ProjectRepository,
    private val scope: CoroutineScope,
) {
    /** Hilt-friendly constructor — uses [defaultCapabilityScope]. */
    @Inject
    constructor(
        settingsRepository: SettingsRepository,
        projectApi: ProjectApi,
        projectRepository: ProjectRepository,
    ) : this(settingsRepository, projectApi, projectRepository, defaultCapabilityScope())

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
        if (!supported) {
            // Drop any cache the user inherited from the previous backend so
            // the projects list doesn't keep rendering stale data (e.g. the
            // shim returning `200 []` would otherwise leave the prior Letta
            // server's catalog visible until the next manual refresh).
            projectRepository.clearCache()
        }
    }
}
