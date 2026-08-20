package com.letta.mobile.data.session

import com.letta.mobile.data.model.VibesyncEvent
import com.letta.mobile.data.repository.api.IVibesyncEventStreamRepository
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted

internal fun defaultSessionScopedVibesyncEventStreamRepositoryScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedVibesyncEventStreamRepository internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IVibesyncEventStreamRepository {
    @Inject
    constructor(sessionManager: SessionManager) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedVibesyncEventStreamRepositoryScope(),
    )

    override val events: SharedFlow<VibesyncEvent> = sessionManager.currentGraph
        .flatMapLatest { it.vibesyncEventStreamRepository.events }
        .shareIn(proxyScope, SharingStarted.Eagerly, replay = 0)

    private val activeSubscribers = AtomicInteger(0)

    init {
        sessionManager.currentGraph
            .onEach { graph ->
                if (activeSubscribers.get() > 0) {
                    graph.vibesyncEventStreamRepository.start()
                }
            }
            .launchIn(proxyScope)
    }

    override fun start() {
        if (activeSubscribers.incrementAndGet() == 1) {
            sessionManager.current.vibesyncEventStreamRepository.start()
        }
    }

    override fun stop() {
        val remaining = activeSubscribers.decrementAndGet()
        if (remaining > 0) return
        activeSubscribers.set(0)
        sessionManager.current.vibesyncEventStreamRepository.stop()
    }

    fun close() { proxyScope.cancel() }
}
