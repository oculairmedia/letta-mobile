package com.letta.mobile.data.repository

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind
import com.letta.mobile.data.model.isIrohBackendUrl
import com.letta.mobile.data.repository.api.ISettingsRepository

/**
 * Debug override for local Iroh testing. MUST stay blank in committed code: a non-blank value forces
 * EVERY backend through Iroh regardless of the active config (breaks local-runtime selection). Set it
 * only in a throwaway local build when dialing a hand-run wrapper.
 *
 * letta-mobile-ckmub.17.1: lives in commonMain so the session graph a browser builds classifies
 * backends with the same predicate the JVM Iroh transport dials on.
 */
internal const val DEBUG_FORCE_IROH_URL = ""

/** Whether [url] routes through an Iroh host (or the [DEBUG_FORCE_IROH_URL] override is set). */
fun shouldUseIrohTransport(url: String?): Boolean = DEBUG_FORCE_IROH_URL.isNotBlank() || isIrohBackendUrl(url)

fun ISettingsRepository.activeBackendIsIroh(): Boolean = shouldUseIrohTransport(this.activeConfig.value?.serverUrl)

/**
 * The [BackendKind] this config routes to, classified with the same predicate the session graph
 * binds the transport on ([shouldUseIrohTransport], which also honours the debug-only force-Iroh
 * override), so chat routing can never disagree with the bound transport. No config yet classifies
 * as [BackendKind.DIRECT_WS].
 *
 * letta-mobile-by4vm.26: replaces `ShimBackendDetector`, a singleton that wrapped this one
 * expression in two derived flows under a name left over from the shim era.
 */
fun LettaConfig?.routedBackendKind(): BackendKind =
    this?.backendKind(forceIroh = shouldUseIrohTransport(serverUrl)) ?: BackendKind.DIRECT_WS
