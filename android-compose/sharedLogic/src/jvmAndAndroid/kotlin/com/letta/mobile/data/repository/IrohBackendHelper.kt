package com.letta.mobile.data.repository

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind
import com.letta.mobile.data.repository.api.ISettingsRepository
import com.letta.mobile.data.transport.iroh.IrohChannelTransport

/**
 * letta-mobile-by4vm.110.6: true when the active backend is an App Server Android reaches through
 * the session's channel (an Iroh host, a ws:// App Server, the on-device runtime), whose admin_rpc
 * the channel's sources call. An http(s) address is another backend family's (the cloud seam,
 * [com.letta.mobile.data.session.SessionRemoteSources]); no address is no backend.
 */
fun ISettingsRepository.activeBackendUsesChannel(): Boolean = activeConfig.value.usesAppServerChannel()

/** See [activeBackendUsesChannel]. */
fun LettaConfig?.usesAppServerChannel(): Boolean {
    val url = this?.serverUrl?.trim().orEmpty()
    return IrohChannelTransport.shouldUseIroh(url) ||
        this?.mode == LettaConfig.Mode.LOCAL ||
        url.startsWith("ws://", ignoreCase = true) ||
        url.startsWith("wss://", ignoreCase = true)
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
