package com.letta.mobile.data.backend

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The one stand-in for a contract a session does not serve. Every call, a
 * property read included, throws [AppServerRouteUnavailable] for [route]; there is no "quietly empty"
 * variant. Callers ask [SessionCapabilities.supports] before touching it, so reaching this is a bug
 * the exception names.
 *
 * Production code binds it only for a whole repository a desktop session graph leaves unbound (the
 * graph's interface has no nullable slot for it until). Android's remote
 * sources are [RemoteRoute.Absent] instead, so no reflection proxy is created in an Android release.
 */
inline fun <reified T : Any> unavailable(route: SessionRoute): T = unavailable(T::class.java, route)

// java.lang.reflect.Proxy is typed on Any; this is the only production boundary that touches it.
@Suppress("NoAnyType")
fun <T : Any> unavailable(contract: Class<T>, route: SessionRoute): T {
    require(contract.isInterface) { "${contract.name} is not an interface" }
    return contract.cast(
        Proxy.newProxyInstance(contract.classLoader, arrayOf(contract), UnavailableRoute(contract.simpleName, route)),
    )
}

/** True when [candidate] is an [unavailable] stand-in (the matrix tests' probe). */
@Suppress("NoAnyType") // Any object may be probed.
fun isUnavailable(candidate: Any?): Boolean =
    candidate != null && Proxy.isProxyClass(candidate.javaClass) && Proxy.getInvocationHandler(candidate) is UnavailableRoute

/** The route an [unavailable] stand-in answers for, or null for anything else. */
@Suppress("NoAnyType") // Any object may be probed.
fun unavailableRouteOf(candidate: Any?): SessionRoute? =
    if (isUnavailable(candidate)) (Proxy.getInvocationHandler(candidate) as UnavailableRoute).route else null

@Suppress("NoAnyType") // InvocationHandler's signature is fixed by java.lang.reflect.
private class UnavailableRoute(private val contractName: String, val route: SessionRoute) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
        when {
            method.declaringClass == Any::class.java -> objectMethod(proxy, method, args)
            else -> throw AppServerRouteUnavailable(route, "$contractName.${method.name}")
        }

    private fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        "toString" -> "Unavailable($contractName, ${route.name})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> null
    }
}
