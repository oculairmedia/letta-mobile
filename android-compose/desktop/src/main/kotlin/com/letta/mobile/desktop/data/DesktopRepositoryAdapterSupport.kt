package com.letta.mobile.desktop.data

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.repository.api.IToolRepository
import com.letta.mobile.data.repository.iroh.IrohAdminRpcAgentDirectory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

internal data class IrohRepositoryBundle(
    val agentRepository: DesktopIrohAgentRepository,
    val scheduleRepository: DesktopIrohScheduleRepository,
    val toolRepository: IToolRepository,
)

internal fun buildIrohRepositories(
    irohMode: Boolean,
    irohAgentDirectoryProvider: () -> IrohAdminRpcAgentDirectory?,
): IrohRepositoryBundle? {
    if (!irohMode) return null
    return IrohRepositoryBundle(
        agentRepository = DesktopIrohAgentRepository(irohAgentDirectoryProvider),
        scheduleRepository = DesktopIrohScheduleRepository(irohAgentDirectoryProvider),
        toolRepository = DesktopIrohToolRepository(irohAgentDirectoryProvider),
    )
}

internal fun buildHttpAdminRepositories(
    config: LettaConfig?,
    irohMode: Boolean,
): DesktopLettaHttpAdminRepositories? =
    config
        ?.takeIf { it.serverUrl.isNotBlank() && !irohMode }
        ?.let(::DesktopLettaHttpAdminRepositories)

internal inline fun <reified T : Any> selectIrohOrHttp(
    iroh: T?,
    http: T?,
): T = iroh ?: http ?: unavailableRepository()

internal inline fun <reified T : Any> unavailableRepository(): T {
    val contract = T::class.java
    val handler = UnavailableRepositoryInvocationHandler(contract.simpleName)
    return Proxy.newProxyInstance(
        contract.classLoader,
        arrayOf(contract),
        handler,
    ) as T
}

internal class UnavailableRepositoryInvocationHandler(
    private val contractName: String,
) : InvocationHandler {
    private val stateFlows = mutableMapOf<String, StateFlow<*>>()
    private val sharedFlows = mutableMapOf<String, SharedFlow<*>>()

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Any::class.java) {
            return when (method.name) {
                "toString" -> "UnavailableDesktopRepository($contractName)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }

        return when {
            StateFlow::class.java.isAssignableFrom(method.returnType) -> {
                val propertyName = method.propertyName()
                stateFlows.getOrPut(propertyName) { defaultStateFlow(propertyName) }
            }
            SharedFlow::class.java.isAssignableFrom(method.returnType) -> {
                val propertyName = method.propertyName()
                sharedFlows.getOrPut(propertyName) { MutableSharedFlow<Any?>() }
            }
            Flow::class.java.isAssignableFrom(method.returnType) -> emptyFlow<Any?>()
            method.returnType == Boolean::class.javaPrimitiveType -> false
            method.returnType == Int::class.javaPrimitiveType -> 0
            method.returnType == Long::class.javaPrimitiveType -> 0L
            method.returnType == Void.TYPE -> null
            method.returnType == List::class.java -> emptyList<Any?>()
            method.returnType == Set::class.java -> emptySet<Any?>()
            method.returnType == Map::class.java -> emptyMap<Any?, Any?>()
            method.name.startsWith("getCached") -> null
            else -> throw DesktopRepositoryUnavailableException(contractName, method.name)
        }
    }

    private fun defaultStateFlow(name: String): StateFlow<*> = when (name) {
        "isRefreshing" -> MutableStateFlow(false)
        "refreshError" -> MutableStateFlow<Throwable?>(null)
        "readyWorkByProject",
        "issuesByProject",
        "issueDetails",
        "issueAnalyticsByProject",
        -> MutableStateFlow(emptyMap<String, Any?>())
        else -> MutableStateFlow(emptyList<Any?>())
    }

    private fun Method.propertyName(): String = when {
        name.startsWith("get") && name.length > 3 -> name.substring(3).replaceFirstChar { it.lowercase() }
        name.startsWith("is") && name.length > 2 -> name.replaceFirstChar { it.lowercase() }
        else -> name
    }
}
