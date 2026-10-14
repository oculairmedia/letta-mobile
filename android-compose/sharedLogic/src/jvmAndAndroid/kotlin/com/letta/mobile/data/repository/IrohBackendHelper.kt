package com.letta.mobile.data.repository

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.iroh.IrohChannelTransport

fun ISettingsRepository.activeBackendIsIroh(): Boolean {
    return IrohChannelTransport.shouldUseIroh(this.activeConfig.value?.serverUrl)
}

/**
 * The [BackendKind] this config routes to, classified with the same predicate the session graph
 * binds the transport on ([IrohChannelTransport.shouldUseIroh], which also honours the debug-only
 * force-Iroh override), so chat routing can never disagree with the bound transport. No config
 * yet classifies as [BackendKind.DIRECT_WS].
 *
 * letta-mobile-by4vm.26: replaces `ShimBackendDetector`, a singleton that wrapped this one
 * expression in two derived flows under a name left over from the shim era.
 */
fun LettaConfig?.routedBackendKind(): BackendKind =
    this?.backendKind(forceIroh = IrohChannelTransport.shouldUseIroh(serverUrl)) ?: BackendKind.DIRECT_WS
