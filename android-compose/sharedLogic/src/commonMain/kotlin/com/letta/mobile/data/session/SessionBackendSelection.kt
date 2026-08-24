package com.letta.mobile.data.session

import com.letta.mobile.data.model.BackendKind
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.model.backendKind

/**
 * Session-graph binding choice: which transport / repo family a host should
 * construct for one [SessionRepositoryGraph] generation.
 *
 * Collapses [BackendKind.SHIM_WS] and [BackendKind.REST] into a single remote
 * HTTP/WS path — session factories only need Local vs Iroh vs everything else.
 *
 * Mode is authoritative: [LettaConfig.Mode.LOCAL] never binds Iroh even when
 * [serverUrl] still carries a leftover `iroh://` ticket (letta-mobile-9v9nu).
 */
enum class SessionBackendBinding {
    LocalRuntime,
    Iroh,
    RemoteHttpOrWs,
}

/**
 * Classify [this] for session factory / transport selection.
 *
 * [forceIroh] should be the platform transport predicate
 * (`IrohChannelTransport.shouldUseIroh`), so debug force-iroh stays aligned
 * with classification — but LOCAL mode still wins (see [backendKind]).
 */
fun LettaConfig?.sessionBackendBinding(
    forceIroh: Boolean = false,
): SessionBackendBinding = when (this?.backendKind(forceIroh = forceIroh)) {
    null, BackendKind.SHIM_WS, BackendKind.REST -> SessionBackendBinding.RemoteHttpOrWs
    BackendKind.LOCAL_RUNTIME -> SessionBackendBinding.LocalRuntime
    BackendKind.IROH -> SessionBackendBinding.Iroh
}

fun SessionBackendBinding.bindsIroh(): Boolean = this == SessionBackendBinding.Iroh

fun SessionBackendBinding.bindsLocalRuntime(): Boolean = this == SessionBackendBinding.LocalRuntime
