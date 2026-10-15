package com.letta.mobile.data.session

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.reflect.KClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Suspend methods (by name; overloads share it) that leave the default `withCurrentSession` path.
 * Every name must exist on the forwarded interface.
 */
data class SessionForwarding(
    /** Run on the current graph without the "switched during operation" post-check. */
    val unchecked: Set<String> = emptySet(),
    /**
     * letta-mobile-xzoy3: idempotent refetches that race the rebuild triggered by the same config
     * change; re-run on the rebuilt graph instead of failing (bounded, see [SESSION_SWITCH_RETRIES]).
     */
    val retryOnSwitch: Set<String> = emptySet(),
)

/**
 * letta-mobile-by4vm.88: the process-lifetime face of one repository of the current session
 * graph, replacing a hand-written SessionScoped* forwarding class per interface. Consumers are
 * process singletons and ViewModels that outlive a graph generation, so a Hilt child component
 * could not hand them the per-generation instance; this forwards each call instead:
 *
 * - suspend: on the current graph inside [SessionRepositoryGraphProvider.withCurrentSession], so a
 *   call that spans a backend switch fails with "Session switched during operation"; see
 *   [SessionForwarding] for the exceptions. `clearForBackendSwitch` always hits the current graph.
 * - returns [StateFlow]: a view whose value is the current graph's and whose collectors follow
 *   rebuilds; the same instance comes back per argument list.
 * - returns [Flow]: `currentGraph.flatMapLatest`, so a flow obtained before a switch follows it.
 * - anything else: the current graph's repository.
 *
 * Interface default methods forward too, so a concrete override is never shadowed by the default.
 * Other [Flow] subtypes (e.g. a shared event stream) are rejected; forward those by hand.
 */
fun <T : Any, G : SessionRepositoryGraph> SessionRepositoryGraphProvider<G>.sessionScoped(
    type: KClass<T>,
    forwarding: SessionForwarding = SessionForwarding(),
    select: (G) -> T,
): T {
    val javaType = type.java
    require(javaType.isInterface) { "${javaType.simpleName} is not an interface" }
    val handler = SessionScopedInvocationHandler(this, javaType, forwarding, select)
    return javaType.cast(Proxy.newProxyInstance(javaType.classLoader, arrayOf(javaType), handler))
}

private const val SESSION_SWITCH_RETRIES = 3
private const val SESSION_REBUILD_WAIT_MS = 300L
private const val SESSION_SWITCHED_MESSAGE = "Session switched during operation"
private const val CLEAR_FOR_BACKEND_SWITCH = "clearForBackendSwitch"

/** Kotlin mangles names of methods taking value classes (`getAgent-abc12`). */
private val Method.baseName: String get() = name.substringBefore('-')
private val Method.isSuspend: Boolean get() = parameterTypes.lastOrNull() == Continuation::class.java

@Suppress("NoAnyType", "UNCHECKED_CAST") // Reflective forwarding: arguments and results are whatever the interface declares.
@OptIn(ExperimentalCoroutinesApi::class)
private class SessionScopedInvocationHandler<T : Any, G : SessionRepositoryGraph>(
    private val provider: SessionRepositoryGraphProvider<G>,
    private val type: Class<T>,
    private val forwarding: SessionForwarding,
    private val select: (G) -> T,
) : InvocationHandler {
    private val stateFlows = ConcurrentHashMap<List<Any?>, StateFlow<Any?>>()

    init {
        type.methods.forEach { method ->
            val returns = method.returnType
            require(!Flow::class.java.isAssignableFrom(returns) || returns == Flow::class.java || returns == StateFlow::class.java) {
                "${type.simpleName}.${method.name} returns ${returns.simpleName}; forward it by hand"
            }
        }
        val names = type.methods.map { it.baseName }.toSet()
        val unknown = (forwarding.unchecked + forwarding.retryOnSwitch) - names
        require(unknown.isEmpty()) { "${type.simpleName} has no methods $unknown" }
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments: Array<Any?> = if (args == null) emptyArray() else Array(args.size) { args[it] }
        return when {
            method.declaringClass == Any::class.java -> objectMethod(proxy, method, arguments)
            method.isSuspend -> forwardSuspend(method, arguments)
            method.returnType == StateFlow::class.java ->
                stateFlows.getOrPut(listOf<Any?>(method) + arguments) { followingStateFlow(method, arguments) }
            method.returnType == Flow::class.java ->
                provider.currentGraph.flatMapLatest { graph -> call(method, select(graph), arguments) as Flow<Any?> }
            else -> call(method, select(provider.current), arguments)
        }
    }

    private fun objectMethod(proxy: Any, method: Method, args: Array<Any?>): Any = when (method.name) {
        "equals" -> proxy === args.single()
        "hashCode" -> System.identityHashCode(proxy)
        else -> "SessionScoped(${type.simpleName})"
    }

    private fun followingStateFlow(method: Method, args: Array<Any?>): StateFlow<Any?> =
        FollowingStateFlow(
            current = { call(method, select(provider.current), args) as StateFlow<Any?> },
            following = provider.currentGraph.flatMapLatest { graph -> call(method, select(graph), args) as StateFlow<Any?> },
        )

    private fun forwardSuspend(method: Method, args: Array<Any?>): Any? {
        val caller = args.last() as Continuation<Any?>
        val callArgs = args.copyOf(args.size - 1)
        val name = method.baseName
        val block: suspend () -> Any? = when {
            name == CLEAR_FOR_BACKEND_SWITCH -> {
                { callSuspend(method, select(provider.current), callArgs) }
            }
            name in forwarding.unchecked -> {
                { callSuspend(method, select(provider.current), callArgs) }
            }
            name in forwarding.retryOnSwitch -> {
                { withCurrentSessionRetryingOnSwitch { graph -> callSuspend(method, select(graph), callArgs) } }
            }
            else -> {
                { provider.withCurrentSession { graph -> callSuspend(method, select(graph), callArgs) } }
            }
        }
        return block.startCoroutineUninterceptedOrReturn(caller)
    }

    private suspend fun callSuspend(method: Method, target: T, args: Array<Any?>): Any? =
        suspendCoroutineUninterceptedOrReturn { continuation -> call(method, target, args + continuation) }

    @Suppress("SpreadOperator") // Method.invoke is Java varargs; args is already this call's own array.
    private fun call(method: Method, target: T, args: Array<Any?>): Any? = try {
        method.invoke(target, *args)
    } catch (e: InvocationTargetException) {
        throw e.cause ?: e
    }

    /**
     * letta-mobile-xzoy3: the rebuild can land mid-call (post-check -> CancellationException) or just
     * after it (the retired transport throws IllegalStateException); both were swallowed upstream as
     * a silent no-op. Re-run against the now-current graph, bounded.
     */
    private suspend fun withCurrentSessionRetryingOnSwitch(block: suspend (G) -> Any?): Any? {
        repeat(SESSION_SWITCH_RETRIES) {
            val graphAtStart = provider.current
            try {
                return provider.withCurrentSession(block)
            } catch (e: CancellationException) {
                if (e.message != SESSION_SWITCHED_MESSAGE) throw e
            } catch (e: IllegalStateException) {
                awaitRebuildOrThrow(graphAtStart, e)
            }
        }
        error("Session graph kept switching; refresh abandoned after $SESSION_SWITCH_RETRIES attempts")
    }

    private suspend fun awaitRebuildOrThrow(graphAtStart: G, error: IllegalStateException) {
        withTimeoutOrNull(SESSION_REBUILD_WAIT_MS) {
            provider.currentGraph.first { it !== graphAtStart }
        } ?: throw error
    }
}

/** The current graph's state: [value] reads it directly, so refresh-then-read never races a mirror. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class FollowingStateFlow<V>(
    private val current: () -> StateFlow<V>,
    private val following: Flow<V>,
) : StateFlow<V> {
    override val value: V get() = current().value
    override val replayCache: List<V> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<V>): Nothing {
        following.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }
}
