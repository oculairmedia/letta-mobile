package com.letta.mobile.data.platform

/** Heap-ratio watermarks shared by the desktop MXBean watcher and the web `performance.memory` poll. */
object HeapPressure {
    const val MODERATE_RATIO: Double = 0.75
    const val CRITICAL_RATIO: Double = 0.90

    fun levelFor(usedBytes: Long, maxBytes: Long): MemoryPressureLevel? {
        if (usedBytes < 0L || maxBytes <= 0L) return null
        val ratio = usedBytes.toDouble() / maxBytes.toDouble()
        return when {
            ratio >= CRITICAL_RATIO -> MemoryPressureLevel.CRITICAL
            ratio >= MODERATE_RATIO -> MemoryPressureLevel.MODERATE
            else -> null
        }
    }

    fun effectiveMax(maxBytes: Long, committedBytes: Long): Long =
        if (maxBytes > 0L) maxBytes else committedBytes
}
