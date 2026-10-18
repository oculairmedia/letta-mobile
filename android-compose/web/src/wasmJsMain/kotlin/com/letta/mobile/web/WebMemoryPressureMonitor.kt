package com.letta.mobile.web

import com.letta.mobile.data.platform.HeapPressure
import com.letta.mobile.data.platform.HeapStats
import com.letta.mobile.data.platform.MemoryPressure
import com.letta.mobile.data.platform.MemoryPressureLevel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope

internal class WebMemoryPressureMonitor(
    private val sample: () -> HeapStats?,
    private val emit: (MemoryPressureLevel) -> Unit = MemoryPressure::emit,
    private val publish: (HeapStats) -> Unit = MemoryPressure::publishStats,
) {
    private var last: MemoryPressureLevel? = null

    fun poll() {
        val stats = sample() ?: return
        publish(stats)
        val level = HeapPressure.levelFor(stats.usedBytes, stats.maxBytes) ?: run {
            last = null
            return
        }
        if (level == last) return
        last = level
        emit(level)
    }
}

internal suspend fun startWebMemoryPressure() {
    val monitor = WebMemoryPressureMonitor(sample = ::sampleWebHeap)
    coroutineScope {
        while (isActive) {
            runCatching { monitor.poll() }
            delay(5.seconds)
        }
    }
}

private fun sampleWebHeap(): HeapStats? {
    val used = webHeapUsed()
    val max = webHeapMax()
    if (used < 0.0 || max <= 0.0) return null
    return HeapStats(used.toLong(), max.toLong())
}

private fun webHeapUsed(): Double =
    js("(typeof performance !== 'undefined' && performance.memory) ? performance.memory.usedJSHeapSize : -1")

private fun webHeapMax(): Double =
    js("(typeof performance !== 'undefined' && performance.memory) ? performance.memory.jsHeapSizeLimit : -1")
