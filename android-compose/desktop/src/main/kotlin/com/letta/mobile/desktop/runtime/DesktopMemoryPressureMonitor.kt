package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.platform.HeapPressure
import com.letta.mobile.data.platform.HeapStats
import com.letta.mobile.data.platform.MemoryPressure
import com.letta.mobile.data.platform.MemoryPressureLevel
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope

internal class DesktopMemoryPressureMonitor(
    private val usedBytes: () -> Long,
    private val maxBytes: () -> Long,
    private val emit: (MemoryPressureLevel) -> Unit = MemoryPressure::emit,
    private val publish: (HeapStats) -> Unit = MemoryPressure::publishStats,
) {
    private var last: MemoryPressureLevel? = null

    fun poll() {
        val used = usedBytes()
        val max = maxBytes()
        publish(HeapStats(used, max))
        val level = HeapPressure.levelFor(used, max) ?: run {
            last = null
            return
        }
        if (level == last) return
        last = level
        emit(level)
    }
}

internal suspend fun runDesktopMemoryPressure() {
    val bean = ManagementFactory.getMemoryMXBean()
    val monitor = DesktopMemoryPressureMonitor(
        usedBytes = { bean.heapMemoryUsage.used },
        maxBytes = {
            val usage = bean.heapMemoryUsage
            HeapPressure.effectiveMax(usage.max, usage.committed)
        },
    )
    coroutineScope {
        while (isActive) {
            runCatching { monitor.poll() }
            delay(5.seconds)
        }
    }
}
