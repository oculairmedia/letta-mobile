package com.letta.mobile.data.timeline

import com.letta.mobile.data.platform.MemoryPressure
import com.letta.mobile.data.platform.MemoryPressureLevel
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

fun interface TimelinePressureTarget {
    fun onTrimmed(kept: List<TimelineMessageId>)
}

/** One open conversation viewport the pressure consumer can shrink. */
class TimelinePressureSession internal constructor(
    val scope: TimelineScope,
    val pageSize: Int,
    private val target: TimelinePressureTarget,
) {
    private val lock = SynchronizedObject()
    private var identities: List<TimelineMessageId> = emptyList()
    private var visible: TimelineVisibleSpan = TimelineVisibleSpan(0, 0)

    fun reportResident(ids: List<TimelineMessageId>) = synchronized(lock) {
        identities = ids
    }

    fun reportVisible(span: TimelineVisibleSpan) = synchronized(lock) {
        visible = span
    }

    fun residentIds(): List<TimelineMessageId> = synchronized(lock) { identities }

    internal fun trimCritical(): List<TimelineMessageId> {
        val kept = synchronized(lock) {
            val range = visible.keepRange(pageSize, identities.size)
            val next = if (range.isEmpty()) identities.take(pageSize) else identities.slice(range)
            identities = next
            next
        }
        target.onTrimmed(kept)
        return kept
    }
}

/** Open presentations register here. The shared consumer trims; hosts never collect. */
class TimelinePressureLedger {
    private val lock = SynchronizedObject()
    private val sessions = mutableListOf<TimelinePressureSession>()

    fun attach(
        scope: TimelineScope,
        pageSize: Int,
        target: TimelinePressureTarget,
    ): TimelinePressureSession {
        val session = TimelinePressureSession(scope, pageSize, target)
        synchronized(lock) { sessions += session }
        return session
    }

    fun detach(session: TimelinePressureSession) = synchronized(lock) {
        sessions.remove(session)
    }

    fun trimCritical(): List<List<TimelineMessageId>> = snapshot().map { it.trimCritical() }

    private fun snapshot(): List<TimelinePressureSession> = synchronized(lock) {
        sessions.toList()
    }
}

/**
 * Timeline-owned ledger. Registers as a shared consumer so `data.platform` never
 * imports this package (no mutual package pair).
 */
object TimelinePressure {
    private val installed = atomic<TimelinePressureLedger?>(null)
    private val bound = atomic(false)

    fun current(): TimelinePressureLedger {
        installed.value?.let { return it }
        val created = TimelinePressureLedger()
        return if (installed.compareAndSet(null, created)) created else requireNotNull(installed.value)
    }

    fun install() {
        current()
        if (!bound.compareAndSet(false, true)) return
        MemoryPressure.current().register { level ->
            if (level == MemoryPressureLevel.CRITICAL) current().trimCritical()
        }
    }

    internal fun resetForTest() {
        installed.value = TimelinePressureLedger()
        bound.value = false
        install()
    }
}
