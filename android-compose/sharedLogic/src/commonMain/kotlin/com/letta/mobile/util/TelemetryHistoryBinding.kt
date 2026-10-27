package com.letta.mobile.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Routes history to a host-owned recorder; it never allocates a default history. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class TelemetryHistoryBinding : StateFlow<List<Telemetry.Event>> {
    private val routingLock = SynchronizedObject()
    private val installed = MutableStateFlow<Installation?>(null)

    override val value: List<Telemetry.Event> get() = installed.value?.recorder?.snapshot().orEmpty()
    override val replayCache: List<List<Telemetry.Event>> get() = listOf(value)

    fun install(recorder: TelemetryRecorder): AutoCloseable = synchronized(routingLock) {
        val installation = Installation(recorder)
        installed.value = installation
        AutoCloseable {
            // Close and append share this linearization point: after close returns no routed write can reach this owner.
            synchronized(routingLock) { installed.compareAndSet(installation, null) }
        }
    }

    fun record(event: Telemetry.Event) = synchronized(routingLock) {
        installed.value?.recorder?.record(event)
        Unit
    }

    fun clear() = synchronized(routingLock) {
        installed.value?.recorder?.clear()
        Unit
    }

    @OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
    override suspend fun collect(collector: FlowCollector<List<Telemetry.Event>>): Nothing {
        installed.flatMapLatest { it?.recorder?.events ?: flowOf(emptyList()) }
            .conflate()
            .distinctUntilChanged()
            .collect(collector)
        error("Telemetry history binding collection completed")
    }

    private class Installation(val recorder: TelemetryRecorder)
}
