package com.letta.mobile.data.backend

/**
 * letta-mobile-ckmub.17.1: the stand-in a host session graph binds for a whole repository it does
 * not serve. Every member, a property read included, throws [AppServerRouteUnavailable] for
 * [unservedRoute]; there is no "quietly empty" variant. Callers ask [SessionCapabilities.supports]
 * before touching it, so reaching one is a bug the exception names.
 *
 * Replaces the JVM reflection proxy production desktop used, so the same graph builds on every
 * target (a browser included). The proxy stays only for JVM test fakes.
 */
interface UnservedRoute {
    val unservedRoute: SessionRoute
}

/** The base of every [UnservedRoute] stand-in: [unserved] names the contract and member that was reached. */
abstract class UnservedStandIn(
    final override val unservedRoute: SessionRoute,
    private val contract: String,
) : UnservedRoute {
    protected fun unserved(member: String): Nothing = throw AppServerRouteUnavailable(unservedRoute, "$contract.$member")

    override fun toString(): String = "Unavailable($contract, ${unservedRoute.name})"
}
