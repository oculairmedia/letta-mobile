package com.letta.mobile.data.capability

import android.util.Log
import com.letta.mobile.data.api.ProjectApi
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.IrohAdminRpcProjectSource
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.session.SessionManager
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * letta-mobile-r52v / letta-mobile-2ixd: tracks which optional endpoints
 * the currently-connected backend supports, so the UI can omit affordances
 * that have nothing to call.
 *
 * Exposes [projectsSupported] (project list/CRUD) and [projectWorkSupported]
 * (issues / ready-work over HTTP). On iroh:// backends project list may work
 * via admin_rpc while project work still has no admin_rpc path.
 *
 * The probe is fired once per active-config identity with a definitive result.
 * Inconclusive Iroh probes — e.g. when [SessionManager] has not rebuilt the
 * Iroh transport yet — are not cached; [observeSessionForRetry] re-probes after
 * the session graph switches.
 */
internal fun defaultCapabilityScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

private sealed class ProbeOutcome {
    data class Definitive(val supported: Boolean) : ProbeOutcome()
    data object Inconclusive : ProbeOutcome()
}

@Singleton
class CapabilityRepository(
    private val settingsRepository: ISettingsRepository,
    private val projectApi: ProjectApi,
    private val irohProjectSource: IrohAdminRpcProjectSource?,
    private val sessionManager: SessionManager?,
    private val scope: CoroutineScope,
) {
    /** Hilt-friendly constructor — uses [defaultCapabilityScope]. */
    @Inject
        constructor(
            settingsRepository: ISettingsRepository,
            projectApi: ProjectApi,
            irohProjectSource: IrohAdminRpcProjectSource,
            sessionManager: SessionManager,
        ) : this(settingsRepository, projectApi, irohProjectSource, sessionManager, defaultCapabilityScope())

    private val _projectsSupported = MutableStateFlow(true)
    val projectsSupported: StateFlow<Boolean> = _projectsSupported.asStateFlow()

    private val _projectWorkSupported = MutableStateFlow(true)
    val projectWorkSupported: StateFlow<Boolean> = _projectWorkSupported.asStateFlow()

    constructor(
        settingsRepository: ISettingsRepository,
        projectApi: ProjectApi,
        scope: CoroutineScope,
    ) : this(settingsRepository, projectApi, null, null, scope)

    private var lastProbedConfigId: String? = null

    init {
        observeConfig()
        observeSessionForRetry()
    }

    private fun observeConfig() {
        scope.launch {
            settingsRepository.activeConfig
                .filterNotNull()
                .distinctUntilChanged { old, new -> old.id == new.id }
                .collect { config ->
                    if (config.id == lastProbedConfigId) return@collect
                    if (config.mode == LettaConfig.Mode.LOCAL) {
                        lastProbedConfigId = config.id
                        setProjectsUnsupported(config.id, "local-runtime")
                        syncProjectWorkSupported(config)
                        return@collect
                    }
                    syncProjectWorkSupported(config)
                    applyProbeOutcome(config, probeProjects(config.id))
                }
        }
    }

    private fun observeSessionForRetry() {
        val manager = sessionManager ?: return
        scope.launch {
            manager.currentGraph
                .distinctUntilChanged { old, new -> old.id == new.id }
                .collect {
                    val config = settingsRepository.activeConfig.value ?: return@collect
                    if (config.id == lastProbedConfigId) return@collect
                    if (config.mode == LettaConfig.Mode.LOCAL) return@collect
                    applyProbeOutcome(config, probeProjects(config.id))
                }
        }
    }

    private fun applyProbeOutcome(config: LettaConfig, outcome: ProbeOutcome) {
        when (outcome) {
            is ProbeOutcome.Definitive -> {
                lastProbedConfigId = config.id
                _projectsSupported.value = outcome.supported
                syncProjectWorkSupported(config)
            }
            ProbeOutcome.Inconclusive -> Unit
        }
    }

    private suspend fun probeProjects(configId: String): ProbeOutcome {
        Telemetry.event(
            "Capability", "probe.attempted",
            "capability" to "projects",
            "configId" to configId,
        )
        val outcome = try {
            val source = irohProjectSource
            if (source != null && source.shouldUseIroh()) {
                probeIrohProjects(source)
            } else {
                ProbeOutcome.Definitive(projectApi.probeAvailability())
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w("CapabilityRepository", "Project capability probe failed", e)
            if (isTransportNotReady(e)) ProbeOutcome.Inconclusive else ProbeOutcome.Definitive(false)
        }
        if (outcome is ProbeOutcome.Definitive) {
            Telemetry.event(
                "Capability", "probe.result",
                "capability" to "projects",
                "configId" to configId,
                "supported" to outcome.supported,
            )
        } else {
            Telemetry.event(
                "Capability", "probe.deferred",
                "capability" to "projects",
                "configId" to configId,
                "reason" to "transport-not-ready",
            )
        }
        return outcome
    }

    private suspend fun probeIrohProjects(source: IrohAdminRpcProjectSource): ProbeOutcome {
        if (sessionManager != null && sessionManager.current.channelTransport !is IrohChannelTransport) {
            return ProbeOutcome.Inconclusive
        }
        return try {
            source.refreshProjects(limit = 1)
            ProbeOutcome.Definitive(true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            if (isTransportNotReady(e)) ProbeOutcome.Inconclusive else ProbeOutcome.Definitive(false)
        }
    }

    private fun syncProjectWorkSupported(config: LettaConfig) {
        val workViaHttp = config.mode != LettaConfig.Mode.LOCAL && !config.activeBackendIsIroh()
        _projectWorkSupported.value = _projectsSupported.value && workViaHttp
    }

    private fun setProjectsUnsupported(configId: String, reason: String) {
        _projectsSupported.value = false
        Telemetry.event(
            "Capability", "probe.skipped",
            "capability" to "projects",
            "configId" to configId,
            "reason" to reason,
        )
    }

    private fun LettaConfig.activeBackendIsIroh(): Boolean =
        IrohChannelTransport.shouldUseIroh(serverUrl)

    private fun isTransportNotReady(error: Throwable): Boolean {
        if (error is UnsupportedOperationException) return true
        if (error is IllegalStateException &&
            error.message.orEmpty().contains("admin_rpc is not supported", ignoreCase = true)
        ) {
            return true
        }
        return error.message.orEmpty().contains("connection not ready", ignoreCase = true) ||
            error.message.orEmpty().contains("No channel transport implementation", ignoreCase = true)
    }
}
