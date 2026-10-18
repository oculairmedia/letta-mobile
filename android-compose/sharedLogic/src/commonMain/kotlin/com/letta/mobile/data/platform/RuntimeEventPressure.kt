package com.letta.mobile.data.platform

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Tightens retained runtime-event copies on CRITICAL. In-flight App Server
 * control-request buffers are left alone so a trim cannot wedge a turn.
 */
fun interface RuntimeEventRetention {
    fun retain(maxEvents: Int)
}

class RuntimeEventPressure {
    private val lock = SynchronizedObject()
    private val retentions = mutableListOf<RuntimeEventRetention>()

    fun bind(retention: RuntimeEventRetention): () -> Unit = synchronized(lock) {
        retentions += retention
        return { unbind(retention) }
    }

    fun onCritical() {
        snapshot().forEach { it.retain(CRITICAL_CAP) }
    }

    private fun unbind(retention: RuntimeEventRetention) = synchronized(lock) {
        retentions.remove(retention)
    }

    private fun snapshot(): List<RuntimeEventRetention> = synchronized(lock) {
        retentions.toList()
    }

    companion object {
        const val CRITICAL_CAP: Int = 32
    }
}

/** Testable ring of retained event ids. Production diagnostics can bind one. */
class BoundedRuntimeEventLog(
    private val normalCap: Int = 256,
) : RuntimeEventRetention {
    private val lock = SynchronizedObject()
    private val ids = ArrayDeque<String>()

    fun append(eventKey: String) = synchronized(lock) {
        ids.addLast(eventKey)
        trimLocked(normalCap)
    }

    override fun retain(maxEvents: Int) = synchronized(lock) {
        trimLocked(maxEvents)
    }

    fun size(): Int = synchronized(lock) { ids.size }

    fun snapshot(): List<String> = synchronized(lock) { ids.toList() }

    private fun trimLocked(maxEvents: Int) {
        while (ids.size > maxEvents) ids.removeFirst()
    }
}
