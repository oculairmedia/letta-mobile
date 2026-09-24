package com.letta.mobile.data.health

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies the active backend (see [BackendKind]) from config truth alone.
 *
 * letta-mobile-g70jb.4: the legacy shim WebSocket pathway is gone, so this no
 * longer health-probes `/v1/health` for a shim marker. A config is local,
 * Iroh, or REST, and none of that needs the network.
 *
 * [activeUsesChannelTransport] answers "is this backend served by a duplex
 * frame channel?", which is now true only for Iroh. A leftover shim-era config
 * classifies as [BackendKind.REST] and sends over REST like any other.
 */
@Singleton
class ShimBackendDetector internal constructor(
    private val activeConfig: StateFlow<LettaConfig?>,
) {
    @Inject
    constructor(settingsRepository: SettingsRepository) : this(settingsRepository.activeConfig)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeBackendKind: StateFlow<BackendKind> = activeConfig
        .map(::kindOf)
        .stateIn(scope, SharingStarted.Eagerly, kindOf(activeConfig.value))

    /** True only for Iroh: the one backend served by an `IChannelTransport`. */
    val activeUsesChannelTransport: StateFlow<Boolean> = activeBackendKind
        .map { it.usesChannelTransport }
        .stateIn(scope, SharingStarted.Eagerly, kindOf(activeConfig.value).usesChannelTransport)

    // The cached accessors are computed directly rather than read off the
    // shared StateFlows: those are `stateIn`-ed on a background IO scope, so a
    // caller that reads them immediately after a config change can observe the
    // previous value. Callers use these as the seed for their own `stateIn`, so
    // a stale seed is a visible first-frame misroute.
    fun cachedActiveUsesChannelTransport(): Boolean = cachedActiveBackendKind().usesChannelTransport

    fun cachedActiveBackendKind(): BackendKind = kindOf(activeConfig.value)
}

/**
 * lgns8.10.4.1: classification must agree with the transport binding.
 * `DefaultSessionRepositoryGraphFactory` binds `IrohChannelTransport` on
 * [IrohChannelTransport.shouldUseIroh], which is `isIrohUrl(url)` OR the
 * debug-only `DEBUG_FORCE_IROH_URL` override, so the classifier feeds that
 * predicate in rather than keying on the URL shape alone.
 */
private fun kindOf(config: LettaConfig?): BackendKind =
    config?.backendKind(forceIroh = IrohChannelTransport.shouldUseIroh(config.serverUrl)) ?: BackendKind.REST
