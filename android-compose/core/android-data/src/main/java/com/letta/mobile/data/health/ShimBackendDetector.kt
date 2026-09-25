package com.letta.mobile.data.health

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind
import com.letta.mobile.data.repository.SettingsRepository
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    // Derived without a scope: the classification is a pure function of the config, so the
    // flows below compute it from the config's current value on every read. Nothing is shared
    // eagerly on a background scope, and a read right after a config change never observes the
    // previous classification.
    val activeBackendKind: StateFlow<BackendKind> = DerivedStateFlow(activeConfig, ::kindOf)

    /** True only for Iroh: the one backend served by an `IChannelTransport`. */
    val activeUsesChannelTransport: StateFlow<Boolean> =
        DerivedStateFlow(activeBackendKind) { it.usesChannelTransport }

    // Callers use these as the seed for their own `stateIn`; they read the same current value
    // the flows expose.
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

/** A [StateFlow] whose value is [transform] of [source]'s current value; it owns no coroutine. */
private class DerivedStateFlow<T, R>(
    private val source: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    override val value: R get() = transform(source.value)
    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map(transform).distinctUntilChanged().collect(collector)
        error("a StateFlow never completes")
    }
}
