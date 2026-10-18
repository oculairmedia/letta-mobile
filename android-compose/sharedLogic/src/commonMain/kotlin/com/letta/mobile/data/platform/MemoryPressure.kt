package com.letta.mobile.data.platform

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How hard the host is pressing on resident memory. */
enum class MemoryPressureLevel {
    MODERATE,
    CRITICAL,
}

/** Last published heap sample. Diagnostics (zxk2n.18) can read [usedBytes] / [maxBytes]. */
data class HeapStats(
    val usedBytes: Long,
    val maxBytes: Long,
)

fun interface MemoryPressureConsumer {
    fun onPressure(level: MemoryPressureLevel)
}

/**
 * letta-mobile-k5nbq.2: one hub per process, owned by the first host that emits
 * or the first shared consumer that attaches. Mutable tables live on this
 * instance, not on a process-global object.
 */
class MemoryPressureHub {
    val caches: MemoryCachePorts = MemoryCachePorts()
    val events: RuntimeEventPressure = RuntimeEventPressure()

    private val lock = SynchronizedObject()
    private val consumers = mutableListOf<MemoryPressureConsumer>()
    private val last = MutableStateFlow<MemoryPressureLevel?>(null)
    private val heap = MutableStateFlow<HeapStats?>(null)

    val lastLevel: StateFlow<MemoryPressureLevel?> = last.asStateFlow()
    val stats: StateFlow<HeapStats?> = heap.asStateFlow()

    fun emit(level: MemoryPressureLevel) {
        last.value = level
        dispatchBuiltins(level)
        snapshotConsumers().forEach { it.onPressure(level) }
    }

    fun publishStats(stats: HeapStats) {
        heap.value = stats
    }

    fun register(consumer: MemoryPressureConsumer): () -> Unit = synchronized(lock) {
        consumers += consumer
        return { unregister(consumer) }
    }

    private fun dispatchBuiltins(level: MemoryPressureLevel) {
        caches.drop(MemoryCacheKind.Images)
        if (level != MemoryPressureLevel.CRITICAL) return
        caches.drop(MemoryCacheKind.Canvas)
        caches.drop(MemoryCacheKind.Attachments)
        events.onCritical()
    }

    private fun unregister(consumer: MemoryPressureConsumer) = synchronized(lock) {
        consumers.remove(consumer)
    }

    private fun snapshotConsumers(): List<MemoryPressureConsumer> = synchronized(lock) {
        consumers.toList()
    }
}

/**
 * Hosts emit; shared consumers attach through [current]. The atomic slot is
 * the only process-wide field — the mutable tables live on [MemoryPressureHub].
 */
object MemoryPressure {
    private val installed = atomic<MemoryPressureHub?>(null)

    fun current(): MemoryPressureHub {
        installed.value?.let { return it }
        val created = MemoryPressureHub()
        return if (installed.compareAndSet(null, created)) created else requireNotNull(installed.value)
    }

    fun emit(level: MemoryPressureLevel) = current().emit(level)

    fun publishStats(stats: HeapStats) = current().publishStats(stats)

    val lastLevel: StateFlow<MemoryPressureLevel?> get() = current().lastLevel
    val stats: StateFlow<HeapStats?> get() = current().stats

    internal fun resetForTest() {
        installed.value = MemoryPressureHub()
    }
}
