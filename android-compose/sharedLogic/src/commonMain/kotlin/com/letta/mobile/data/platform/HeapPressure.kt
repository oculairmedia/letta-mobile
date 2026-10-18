package com.letta.mobile.data.platform

import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Live-heap watermarks shared by the desktop collection-usage listener and the web heap poll. */
object HeapPressure {
    const val MODERATE_RATIO: Double = 0.75
    const val CRITICAL_RATIO: Double = 0.90

    fun levelFor(ratio: Double): MemoryPressureLevel? = when {
        ratio >= CRITICAL_RATIO -> MemoryPressureLevel.CRITICAL
        ratio >= MODERATE_RATIO -> MemoryPressureLevel.MODERATE
        else -> null
    }

    fun effectiveMax(maxBytes: Long, committedBytes: Long): Long =
        if (maxBytes > 0L) maxBytes else committedBytes
}

/**
 * Turns heap readings into pressure signals, counting only memory that is actually retained.
 *
 * Raw heap usage includes garbage the collector has not reached yet, so a healthy heap reads as
 * nearly full just before every collection. The desktop host reads usage measured right after a
 * collection, which is already live data: it passes a zero [floorWindow]. The browser exposes no
 * collection events, so web passes a window spanning several collections and only the lowest
 * reading inside it counts - the collections in that window pull the floor down to what survives
 * them. No level is reported until one full window has been observed.
 *
 * A rise is reported at once; a level that holds is reported again at most every [repeatAfter], so
 * sustained pressure keeps caches small without dropping them on every sample.
 */
class HeapPressureGate(
    private val floorWindow: Duration,
    private val repeatAfter: Duration,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private class Reading(val at: ComparableTimeMark, val ratio: Double)

    private val readings = ArrayDeque<Reading>()
    private var firstReading: ComparableTimeMark? = null
    private var held: MemoryPressureLevel? = null
    private var reportedAt: ComparableTimeMark? = null

    /** The level to emit for this reading, or null when there is nothing new to report. */
    fun offer(usedBytes: Long, maxBytes: Long): MemoryPressureLevel? {
        if (usedBytes < 0L || maxBytes <= 0L) return null
        val now = timeSource.markNow()
        val first = firstReading ?: now.also { firstReading = it }
        readings.addLast(Reading(now, usedBytes.toDouble() / maxBytes.toDouble()))
        // The newest reading always counts; older ones only while they fall inside the window.
        while (readings.size > 1 && now - readings.first().at >= floorWindow) readings.removeFirst()
        if (now - first < floorWindow) return null
        return admit(HeapPressure.levelFor(readings.minOf { it.ratio }), now)
    }

    private fun admit(level: MemoryPressureLevel?, now: ComparableTimeMark): MemoryPressureLevel? {
        if (level == null) {
            held = null
            return null
        }
        val previous = held
        val last = reportedAt
        val news = previous == null || level > previous || last == null || now - last >= repeatAfter
        if (!news) return null
        held = level
        reportedAt = now
        return level
    }
}
