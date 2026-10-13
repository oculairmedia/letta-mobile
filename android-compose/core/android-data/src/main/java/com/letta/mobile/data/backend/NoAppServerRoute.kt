package com.letta.mobile.data.backend

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * letta-mobile-ckmub.4: thrown when a call has no route on the active backend.
 *
 * The app talks to exactly one kind of backend, a letta-code App Server (on-device, direct, or over
 * an Iroh host). The Letta REST backend that used to answer everything else is gone, so an
 * operation the App Server (or the Iroh host's `admin_rpc` relay) does not offer fails with this
 * instead of falling back to some other server.
 */
class NoAppServerRouteException(operation: String) : UnsupportedOperationException(
    "$operation has no route on this backend: only the letta-code App Server is supported " +
        "(the Letta REST backend was removed).",
)

/**
 * A [T] remote source whose every call fails with [NoAppServerRouteException].
 *
 * The remote-source interfaces (`AgentRemoteSource`, `ToolRemoteSource`, ...) are the seam a
 * future non-App-Server backend (e.g. a cloud backend) would implement; see
 * [com.letta.mobile.data.session.SessionRemoteSources]. Until one exists, every repository is bound
 * to this, and its App Server, Iroh or on-device source serves what the backend can do.
 */
inline fun <reified T : Any> noAppServerRoute(): T = noAppServerRoute(T::class.java)

// java.lang.reflect.Proxy is typed on Any; this is the only boundary that touches it.
@Suppress("NoAnyType")
fun <T : Any> noAppServerRoute(contract: Class<T>): T {
    require(contract.isInterface) { "${contract.name} is not an interface" }
    return contract.cast(
        Proxy.newProxyInstance(contract.classLoader, arrayOf(contract), NoAppServerRouteHandler(contract.simpleName)),
    )
}

@Suppress("NoAnyType") // InvocationHandler's signature is fixed by java.lang.reflect.
private class NoAppServerRouteHandler(private val contractName: String) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
        when {
            method.declaringClass == Any::class.java -> objectMethod(proxy, method, args)
            else -> throw NoAppServerRouteException("$contractName.${method.name}")
        }

    private fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        "toString" -> "NoAppServerRoute($contractName)"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.firstOrNull()
        else -> null
    }
}
