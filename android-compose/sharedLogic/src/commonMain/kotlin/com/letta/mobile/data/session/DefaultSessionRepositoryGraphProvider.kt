package com.letta.mobile.data.session

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-neutral session graph provider: rebuild closes the previous graph,
 * publishes the next generation, and surfaces create failures on [sessionError].
 *
 * Android `SessionManager` and desktop `DesktopSessionGraphProvider` both extend
 * this type so rebuild / switch semantics stay identical across hosts.
 */
open class DefaultSessionRepositoryGraphProvider<Graph : SessionRepositoryGraph>(
    private val factory: SessionRepositoryGraphFactory<Graph>,
    private val sessionSwitchMessage: String = "Session switched during operation",
) : SessionRepositoryGraphProvider<Graph> {
    private val rebuildLock = SynchronizedObject()
    private val currentGraphFlow = MutableStateFlow(factory.create())
    private val sessionErrorFlow = MutableStateFlow<Throwable?>(null)

    override val currentGraph: StateFlow<Graph> = currentGraphFlow.asStateFlow()
    override val sessionError: StateFlow<Throwable?> = sessionErrorFlow.asStateFlow()

    override val current: Graph
        get() = currentGraphFlow.value

    override fun rebuild(): Graph = synchronized(rebuildLock) {
        val previous = currentGraphFlow.value
        try {
            val next = factory.create()
            currentGraphFlow.value = next
            previous.close()
            sessionErrorFlow.value = null
            next
        } catch (t: Throwable) {
            sessionErrorFlow.value = t
            throw t
        }
    }

    override suspend fun <T> withCurrentSession(block: suspend (Graph) -> T): T {
        val graph = current
        val result = block(graph)
        if (current !== graph) {
            throw CancellationException(sessionSwitchMessage)
        }
        return result
    }
}
