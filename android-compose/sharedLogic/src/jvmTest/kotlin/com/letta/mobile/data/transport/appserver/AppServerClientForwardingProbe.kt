package com.letta.mobile.data.transport.appserver

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * letta-mobile-by4vm.83: drives every [AppServerClient] request through a wrapper and reports which
 * ones reached the wrapped client. Behavioural on purpose: on the JVM, Kotlin compiles an
 * un-overridden interface default into a stub on the implementing class that calls the default
 * body, so reflection names the wrapper as the declarer of every request whether it forwards or
 * not. Only calling the request tells a forwarder from a stub that throws
 * UnsupportedOperationException.
 */
internal object AppServerClientForwardingProbe {
    /** Every request on [AppServerClient]: each suspend member (the JVM's trailing Continuation). */
    val requests: List<Method> = AppServerClient::class.java.methods
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .filter { it.parameterTypes.lastOrNull() == Continuation::class.java }
        .sortedBy { it.name }

    /** Thrown by a [recordingClient] once it has recorded the request, so nothing waits on a reply. */
    class Forwarded(val request: String) : RuntimeException(request)

    /**
     * An [AppServerClient] that records the name of every request it receives into [seen] (as
     * "`lane`:`request`") and then throws [Forwarded]. Its flows are inert.
     */
    fun recordingClient(lane: String, seen: MutableList<String>): AppServerClient =
        proxyOf(AppServerClient::class.java) { method, _ ->
            when (method.name) {
                "getEvents" -> emptyFlow<AppServerReceivedFrame>()
                "isConnected" -> flowOf(true)
                "getServerInfo" -> MutableStateFlow<AppServerInfoData?>(null)
                "toString" -> "recording:$lane"
                "hashCode" -> lane.hashCode()
                "equals" -> false
                else -> {
                    seen += "$lane:${method.name}"
                    throw Forwarded(method.name)
                }
            }
        }

    /** The recording clients ("lanes") behind [wrapper] that received [request], per [seen]. */
    fun lanesReached(wrapper: AppServerClient, request: Method, seen: MutableList<String>): List<String> {
        seen.clear()
        call(wrapper, request)
        return seen.filter { it.substringAfter(':') == request.name }.map { it.substringBefore(':') }
    }

    /** The requests that reached no recording client behind [wrapper]. */
    fun unforwarded(wrapper: AppServerClient, seen: MutableList<String>): List<String> =
        requests.filter { lanesReached(wrapper, it, seen).isEmpty() }.map { it.name }

    /**
     * Calls [request] on [wrapper] synchronously. Both wrappers hand a request to their delegate
     * before their first suspension point, so the recording client has seen it (or the interface
     * default has thrown) by the time `invoke` returns; a wrapper that suspends first reads as
     * unforwarded rather than hanging the test.
     */
    private fun call(wrapper: AppServerClient, request: Method) {
        val args = request.parameterTypes.dropLast(1).map { placeholder(it) }
        val ignoreResume = Continuation<Any?>(EmptyCoroutineContext) {}
        try {
            request.invoke(wrapper, *args.toTypedArray(), ignoreResume)
        } catch (_: InvocationTargetException) {
            // Forwarded, UnsupportedOperationException from a default, or a wrapper's own refusal.
        }
    }

    /**
     * A non-null stand-in of [type] for a wrapper to pass through: one of its subtypes when it is
     * sealed, a proxy for an open interface, an un-constructed instance for a class (wrappers
     * forward commands, they do not read them).
     */
    private fun placeholder(type: Class<*>): Any = when {
        type.isSealed -> placeholder(type.permittedSubclasses.first())
        type.isInterface -> proxyOf(type) { method, _ -> defaultFor(method.returnType) }
        else -> unsafe.allocateInstance(type)
    }

    private fun defaultFor(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        String::class.java -> ""
        else -> null
    }

    private fun <T> proxyOf(type: Class<T>, answer: (Method, Array<out Any?>?) -> Any?): T {
        val handler = InvocationHandler { _, method, args -> answer(method, args) }
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
    }

    private val unsafe: sun.misc.Unsafe by lazy {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        field.get(null) as sun.misc.Unsafe
    }

    /** A thread-safe list for [recordingClient] (a wrapper may hop dispatchers). */
    fun newLog(): MutableList<String> = Collections.synchronizedList(mutableListOf())
}
