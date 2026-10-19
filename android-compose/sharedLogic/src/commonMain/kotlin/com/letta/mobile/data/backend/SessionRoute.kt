package com.letta.mobile.data.backend

import com.letta.mobile.data.chat.recompile.IrohConversationRecompileRpc

/**
 * Every optional route a session graph may or may not serve.
 *
 * This is the compile-time-checked list behind [SessionCapabilities] and [AppServerRouteUnavailable]:
 * a route a backend does not serve is named by one of these, never by a free-form string, and every
 * `when` over it (the [SessionRouteMatrix] rows, each factory's binding probe) must handle a new case
 * before the build passes.
 *
 * [hostCapability] is the `auth_response` feature an Iroh host must advertise for the route to be
 * live on top of the backend kind serving it; null when the backend kind alone decides.
 */
enum class SessionRoute(val hostCapability: String? = null) {
    AGENTS,
    BLOCKS,
    CONVERSATIONS,
    CONVERSATION_RECOMPILE(hostCapability = IrohConversationRecompileRpc.CAPABILITY),
    MCP_SERVERS,
    MODELS,
    PASSAGES,
    PROJECTS,
    PROJECT_WORK,
    PROVIDERS,
    RUNS,
    SCHEDULES,
    SKILLS,
    STEPS,
    TOOLS,
    VIBESYNC,
}

/**
 * The last-resort backstop: a call reached a [route] the session does not serve.
 *
 * Callers are expected to ask [SessionCapabilities.supports] (or pattern-match a [RemoteRoute])
 * first; this is what an unguarded call fails with instead of reaching some other server. It is an
 * [UnsupportedOperationException] so the existing "not here" mappings keep reading it.
 */
class AppServerRouteUnavailable(
    val route: SessionRoute,
    operation: String? = null,
) : UnsupportedOperationException(
    "${operation ?: route.name} has no route on this backend (${route.name} is not served by this session).",
)

/**
 * A remote source a session either binds or does not.
 *
 * Replaces the reflection proxies that used to stand in for an absent source: an [Absent] route is a
 * value callers can match on, and [source] throws [AppServerRouteUnavailable] only as the backstop.
 */
sealed interface RemoteRoute<out T : Any> {
    data class Bound<out T : Any>(val value: T) : RemoteRoute<T>

    data class Absent(val route: SessionRoute) : RemoteRoute<Nothing>

    val isBound: Boolean get() = this is Bound<*>

    /** The bound source or null, for callers that have their own answer when it is absent. */
    fun orNull(): T? = when (this) {
        is Bound -> value
        is Absent -> null
    }

    /** The bound source; the backstop [AppServerRouteUnavailable] when absent. */
    fun source(operation: String? = null): T = when (this) {
        is Bound -> value
        is Absent -> throw AppServerRouteUnavailable(route, operation)
    }
}

/** [this] as a bound route. */
fun <T : Any> T.bound(): RemoteRoute<T> = RemoteRoute.Bound(this)

/** [this] bound when non-null, otherwise [route] absent. */
fun <T : Any> T?.routeOr(route: SessionRoute): RemoteRoute<T> = this?.let { RemoteRoute.Bound(it) } ?: RemoteRoute.Absent(route)
