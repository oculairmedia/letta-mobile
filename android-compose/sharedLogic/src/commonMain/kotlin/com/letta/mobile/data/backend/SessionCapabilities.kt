package com.letta.mobile.data.backend

import com.letta.mobile.data.model.BackendKind

/** The hosts that build a session graph (each has one factory). */
enum class SessionPlatform {
    ANDROID,
    DESKTOP,
    WEB,
}

/**
 * What one session serves, decided once when its graph is built.
 *
 * Callers ask [supports] before reaching for an optional route, and render "not available" (the
 * existing reason text) instead of calling it. [AppServerRouteUnavailable] is only the backstop for
 * a call that skipped the question. [bound] is fixed for the session; a route that also needs an Iroh
 * host feature ([SessionRoute.hostCapability]) reads [hostCapabilities] live, since a host advertises
 * its features on the handshake, after the graph exists.
 */
class SessionCapabilities(
    val bound: Set<SessionRoute>,
    private val hostCapabilities: () -> Set<String> = { emptySet() },
) {
    fun supports(route: SessionRoute): Boolean {
        if (route !in bound) return false
        val feature = route.hostCapability ?: return true
        return feature in hostCapabilities()
    }

    override fun toString(): String = "SessionCapabilities(${bound.sorted().joinToString()})"

    companion object {
        /** A session with no backend configured: nothing is served. */
        val None: SessionCapabilities = SessionCapabilities(emptySet())

        /**
         * [platform]'s production row of the [SessionRouteMatrix] for [kind] (null: no backend
         * configured), plus [extraBound] routes the host bound beyond it (Android's remote-source seam,
         * which production leaves empty).
         */
        fun forSession(
            platform: SessionPlatform,
            kind: BackendKind?,
            hostCapabilities: () -> Set<String> = { emptySet() },
            extraBound: Set<SessionRoute> = emptySet(),
        ): SessionCapabilities = SessionCapabilities(SessionRouteMatrix.bound(platform, kind) + extraBound, hostCapabilities)
    }
}

/**
 * The (platform x backend kind x route) contract. This replaces the
 * "adding a backend means editing three factories" note: a new [BackendKind] or [SessionRoute] does
 * not compile until every row below decides it, and each platform's matrix test fails until its
 * factory binds exactly its row. Where the platforms disagree, [DIVERGENCES] must say why.
 */
object SessionRouteMatrix {
    /** What an Iroh host relays over `admin_rpc` for every platform with an admin surface. */
    private val IROH_ADMIN = setOf(
        SessionRoute.AGENTS,
        SessionRoute.BLOCKS,
        SessionRoute.MCP_SERVERS,
        SessionRoute.MODELS,
        SessionRoute.PROJECTS,
        SessionRoute.PROVIDERS,
        SessionRoute.RUNS,
        SessionRoute.SCHEDULES,
        SessionRoute.SKILLS,
        SessionRoute.TOOLS,
    )

    fun bound(platform: SessionPlatform, kind: BackendKind?): Set<SessionRoute> = when (platform) {
        SessionPlatform.ANDROID -> android(kind)
        SessionPlatform.DESKTOP -> desktop(kind)
        SessionPlatform.WEB -> web(kind)
    }

    /**
     * Android: every backend is an App Server reached through the session's channel (an Iroh host, a
     * ws:// address, or the on-device runtime's loopback server), whose
     * admin_rpc is the Iroh host's router or the same router run in process.
     */
    private fun android(kind: BackendKind?): Set<SessionRoute> = when (kind) {
        null -> emptySet()
        BackendKind.LOCAL_RUNTIME, BackendKind.DIRECT_WS, BackendKind.IROH ->
            IROH_ADMIN + SessionRoute.CONVERSATIONS + SessionRoute.CONVERSATION_RECOMPILE
    }

    /**
     * Desktop: a node serves the admin set, its own in-process node for the bundled runtime exactly as an Iroh node
     * does; a ws:// App Server it dials directly (a developer-only setting) serves agents and memory.
     */
    private fun desktop(kind: BackendKind?): Set<SessionRoute> = when (kind) {
        null -> emptySet()
        BackendKind.LOCAL_RUNTIME, BackendKind.IROH -> IROH_ADMIN
        BackendKind.DIRECT_WS -> setOf(SessionRoute.AGENTS, SessionRoute.BLOCKS)
    }

    /**
     * Whether [platform]'s session for [kind] is a node session: the shell attaches to a node (over Iroh, or the
     * desktop's own node in its process) and reads the admin set over `admin_rpc` on that session's channel transport.
     * Android builds its own graph and is not asked.
     */
    fun attachesThroughNode(platform: SessionPlatform, kind: BackendKind?): Boolean = when (kind) {
        BackendKind.IROH -> true
        BackendKind.LOCAL_RUNTIME -> platform == SessionPlatform.DESKTOP
        BackendKind.DIRECT_WS, null -> false
    }

    /**
     * Web: desktop's row (the browser builds desktop's session graph over its
     * one App Server session), except that a browser has no embedded runtime.
     */
    private fun web(kind: BackendKind?): Set<SessionRoute> = when (kind) {
        null, BackendKind.LOCAL_RUNTIME -> emptySet()
        BackendKind.DIRECT_WS, BackendKind.IROH -> desktop(kind)
    }

    /**
     * Every (kind, route) cell where the platforms do not all agree, with the reason. The matrix test
     * fails when a cell disagrees without an entry here, or an entry no longer disagrees.
     */
    val DIVERGENCES: Map<Pair<BackendKind?, SessionRoute>, String> = buildMap {
        put(BackendKind.IROH to SessionRoute.CONVERSATIONS, DESKTOP_CONVERSATIONS)
        put(BackendKind.IROH to SessionRoute.CONVERSATION_RECOMPILE, ONLY_ANDROID_RECOMPILES)
        // The desktop attaches to its own node exactly as to an Iroh node, so its local row diverges from Android's
        // where the Iroh row does, and from the browser's, which has no runtime, everywhere it serves.
        put(BackendKind.LOCAL_RUNTIME to SessionRoute.CONVERSATIONS, DESKTOP_CONVERSATIONS)
        put(BackendKind.LOCAL_RUNTIME to SessionRoute.CONVERSATION_RECOMPILE, ONLY_ANDROID_RECOMPILES)
        for (route in desktop(BackendKind.LOCAL_RUNTIME)) put(BackendKind.LOCAL_RUNTIME to route, NO_BROWSER_RUNTIME)
        // Android reaches a ws:// App Server through the same channel and in-process admin router as an Iroh host;
        // desktop (where it is a developer-only setting) and web bind fewer routes there.
        for (route in android(BackendKind.DIRECT_WS) - desktop(BackendKind.DIRECT_WS)) {
            put(BackendKind.DIRECT_WS to route, ANDROID_APP_SERVER_CHANNEL)
        }
    }

    private const val DESKTOP_CONVERSATIONS =
        "desktop and web list conversations through their chat gateway, not the session's conversation repository"
    private const val ONLY_ANDROID_RECOMPILES = "only Android relays conversation.recompile"
    private const val NO_BROWSER_RUNTIME = "a browser has no embedded runtime"
    private const val ANDROID_APP_SERVER_CHANNEL =
        "Android serves a ws:// App Server through its channel and the Iroh host's admin router; desktop and web bind fewer routes"

    /** The cells where [bound] differs between platforms, computed from the rows. */
    fun disagreements(): Set<Pair<BackendKind?, SessionRoute>> {
        val kinds = listOf<BackendKind?>(null) + BackendKind.entries
        return kinds.flatMap { kind ->
            SessionRoute.entries.filter { route ->
                SessionPlatform.entries.map { route in bound(it, kind) }.distinct().size > 1
            }.map { kind to it }
        }.toSet()
    }
}
