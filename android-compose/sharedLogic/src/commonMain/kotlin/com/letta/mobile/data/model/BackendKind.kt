package com.letta.mobile.data.model

/**
 * letta-mobile-lgns8.10.4.1: the single source of truth for "what kind of
 * backend is this config?".
 *
 * Before this type, four separate copies of an `iroh://`-prefix heuristic
 * decided routing (`ShimBackendDetector`, `ChatSendCoordinator`,
 * `AdminChatViewModel`, `IrohChannelTransport.isIrohUrl`), and the chat send
 * path keyed strategy selection on a boolean literally named
 * `isShimBackend` that returned **true for Iroh backends**. That inversion is
 * why a nominally-Iroh client still selected the shim-shaped send strategy and
 * why nothing structurally stopped it from dialing the LettaShim.
 *
 * Routing decisions must key on this enum, not on URL string sniffing spread
 * across modules.
 */
enum class BackendKind {
    /** On-device embedded runtime — no network transport at all. */
    LOCAL_RUNTIME,

    /** Iroh QUIC node (`iroh://…`). Production transport. Never dials the shim. */
    IROH,

    /**
     * The legacy letta-code admin shim, reached over its mobile WebSocket
     * channel. Retained only for explicitly shim-configured backends.
     */
    SHIM_WS,

    /** Vanilla Letta server / Letta Cloud over plain REST. */
    REST;

    /**
     * True when this backend is served by an [com.letta.mobile.data.transport.api.IChannelTransport]
     * (a duplex frame channel) rather than by REST polling.
     *
     * NOTE: this is the predicate that the chat UI actually wants wherever it
     * historically asked `isShimBackend` — it covers **both** Iroh and the
     * shim WS. Keeping the two questions separate is the whole point: "does
     * this backend stream frames?" is not "is this backend the shim?".
     */
    val usesChannelTransport: Boolean
        get() = this == IROH || this == SHIM_WS

    /** True only for the genuine LettaShim WS backend. */
    val isShim: Boolean
        get() = this == SHIM_WS
}

/**
 * True when the backend is an `iroh://` node (bare, or a corrupted
 * `https://iroh://` saved config). Mirrors `IrohChannelTransport.isIrohUrl`,
 * which is what `SessionGraphFactory` uses to bind the transport — the two
 * MUST agree, otherwise routing and transport disagree about the backend.
 */
fun LettaConfig.isIrohBackend(): Boolean = isIrohBackendUrl(serverUrl)

/** URL-level form of [isIrohBackend], for call sites that only have a string. */
fun isIrohBackendUrl(url: String?): Boolean {
    if (url == null) return false
    return url.trimStart()
        .removePrefix("https://")
        .removePrefix("http://")
        .startsWith("iroh://")
}

/**
 * Classify a config without a network probe.
 *
 * [shimDetected] is the cached result of the `/v1/health` shim probe and is
 * consulted **only** when the config is neither local nor Iroh — an Iroh
 * config must never be health-probed, because that probe is itself an HTTP
 * dial at the shim's address.
 *
 * [forceIroh] lets a caller feed in the platform transport-selection predicate
 * (`IrohChannelTransport.shouldUseIroh`, which also honours the debug-only
 * `DEBUG_FORCE_IROH_URL`) so classification can never disagree with the
 * transport the session graph actually bound.
 */
fun LettaConfig.backendKind(
    shimDetected: Boolean = false,
    forceIroh: Boolean = false,
): BackendKind = when {
    mode == LettaConfig.Mode.LOCAL -> BackendKind.LOCAL_RUNTIME
    forceIroh || isIrohBackend() -> BackendKind.IROH
    shimDetected -> BackendKind.SHIM_WS
    else -> BackendKind.REST
}
