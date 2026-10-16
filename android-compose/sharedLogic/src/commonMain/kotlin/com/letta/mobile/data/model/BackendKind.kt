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
 *
 * letta-mobile-ckmub.9 — the backend seam. Letta Cloud is not offered for now (no Settings mode,
 * no cloud URL default, no cloud API-key field). There is no single registration point for a
 * backend family: a new one needs a [LettaConfig.Mode] value (the Settings page lists
 * `Mode.entries`), a kind here returned by [backendKind], and then each place that binds a
 * transport or picks a send path for it:
 *  - Android: `routedBackendKind` (session graph assembly and `SessionChannelTransportFactory`)
 *    and the chat `ChatSendStrategySelector`;
 *  - desktop: `routedBackendKind` in `HostRepositoryAdapters`, and
 *    `DesktopAppServerChatGateways.createDefault`, which chooses on mode and address;
 *  - web: `WasmAppServerSession`, which still chooses on [isIrohBackendUrl] directly.
 *
 * letta-mobile-by4vm.13: the compiler and the matrix tests then say what else to do. Every row of
 * [com.letta.mobile.data.backend.SessionRouteMatrix] is an exhaustive `when` over this enum, so a new
 * kind does not build until each platform (Android, desktop, web) decides which
 * [com.letta.mobile.data.backend.SessionRoute]s it serves; each platform's matrix test then fails
 * until its session factory binds exactly that row. Nothing in sharedUI keys on the backend family.
 */
enum class BackendKind {
    /**
     * The on-device runtime: a loopback App Server this device hosts (letta-mobile-by4vm.110.6).
     * Android dials it through the same App Server channel as [DIRECT_WS].
     */
    LOCAL_RUNTIME,

    /** Iroh QUIC node (`iroh://…`). Production transport. Never dials the shim. */
    IROH,

    /**
     * A remote App Server dialled directly over its WebSocket address (`ws://…` / `wss://…`): a
     * Settings "Remote host" whose address is not an `iroh://` node. Also the kind of a missing
     * config. letta-mobile-by4vm.26: was `REST`, named for the Letta REST server that
     * letta-mobile-ckmub.4 removed. The kind is derived from [LettaConfig] on every read and never
     * persisted, so the rename needs no settings migration.
     */
    DIRECT_WS,
}

/**
 * True when the backend is an `iroh://` node (bare, or a corrupted
 * `https://iroh://` saved config). Mirrors `IrohChannelTransport.isIrohUrl`,
 * which is what `DefaultSessionRepositoryGraphFactory` uses to bind the transport — the two
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
 * [forceIroh] lets a caller feed in the platform transport-selection predicate
 * (`IrohChannelTransport.shouldUseIroh`, which also honours the debug-only
 * `DEBUG_FORCE_IROH_URL`) so classification can never disagree with the
 * transport the session graph actually bound.
 */
fun LettaConfig.backendKind(forceIroh: Boolean = false): BackendKind = when {
    mode == LettaConfig.Mode.LOCAL -> BackendKind.LOCAL_RUNTIME
    forceIroh || isIrohBackend() -> BackendKind.IROH
    else -> BackendKind.DIRECT_WS
}
