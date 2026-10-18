package com.letta.mobile.web

import com.letta.mobile.data.platform.HeapPressureGate
import com.letta.mobile.data.platform.MemoryPressure
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope

/**
 * `performance.memory` counts garbage the collector has not reached, and the browser reports no
 * collections, so the gate only counts the lowest reading across a window spanning several of them.
 */
internal suspend fun startWebMemoryPressure() {
    val gate = HeapPressureGate(floorWindow = 30.seconds, repeatAfter = 30.seconds)
    coroutineScope {
        while (isActive) {
            val used = webHeapUsed()
            val max = webHeapMax()
            if (used >= 0.0 && max > 0.0) gate.offer(used.toLong(), max.toLong())?.let(MemoryPressure::emit)
            delay(5.seconds)
        }
    }
}

private fun webHeapUsed(): Double =
    js("(typeof performance !== 'undefined' && performance.memory) ? performance.memory.usedJSHeapSize : -1")

private fun webHeapMax(): Double =
    js("(typeof performance !== 'undefined' && performance.memory) ? performance.memory.jsHeapSizeLimit : -1")
