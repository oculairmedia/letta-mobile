package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.platform.HeapStats
import com.letta.mobile.data.platform.MemoryPressureLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopMemoryPressureMonitorTest {
    @Test
    fun emitsOnlyOnLevelChange() {
        val emitted = mutableListOf<MemoryPressureLevel>()
        val stats = mutableListOf<HeapStats>()
        var used = 10L
        val monitor = DesktopMemoryPressureMonitor(
            usedBytes = { used },
            maxBytes = { 100L },
            emit = { emitted += it },
            publish = { stats += it },
        )

        monitor.poll()
        used = 80
        monitor.poll()
        monitor.poll()
        used = 95
        monitor.poll()

        assertEquals(listOf(MemoryPressureLevel.MODERATE, MemoryPressureLevel.CRITICAL), emitted)
        assertEquals(4, stats.size)
    }
}
