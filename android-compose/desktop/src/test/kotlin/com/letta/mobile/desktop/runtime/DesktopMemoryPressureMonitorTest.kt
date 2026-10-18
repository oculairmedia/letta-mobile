package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.platform.HeapPressure
import com.letta.mobile.data.platform.HeapPressureGate
import com.letta.mobile.data.platform.MemoryPressureLevel
import java.lang.management.ManagementFactory
import java.lang.management.MemoryUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class DesktopMemoryPressureMonitorTest {
    private val clock = TestTimeSource()
    private val emitted = mutableListOf<MemoryPressureLevel>()
    private val monitor = DesktopMemoryPressureMonitor(
        gate = HeapPressureGate(floorWindow = Duration.ZERO, repeatAfter = 30.seconds, timeSource = clock),
        emit = { emitted += it },
    )

    private fun afterCollection(used: Long) = MemoryUsage(0, used, 100, 100)

    @Test
    fun postCollectionUsageDrivesTheLevel() {
        monitor.onCollectionUsage(afterCollection(80))
        monitor.onCollectionUsage(afterCollection(82))
        monitor.onCollectionUsage(afterCollection(95))

        assertEquals(listOf(MemoryPressureLevel.MODERATE, MemoryPressureLevel.CRITICAL), emitted)
    }

    @Test
    fun collectionsThatStayCriticalRepeatOnlyAfterTheInterval() {
        monitor.onCollectionUsage(afterCollection(95))
        clock += 5.seconds
        monitor.onCollectionUsage(afterCollection(96))
        clock += 30.seconds
        monitor.onCollectionUsage(afterCollection(96))

        assertEquals(listOf(MemoryPressureLevel.CRITICAL, MemoryPressureLevel.CRITICAL), emitted)
    }

    @Test
    fun installingArmsTheTenuredPoolAtTheModerateWatermarkAndClosingDisarmsIt() {
        val tenured = ManagementFactory.getMemoryPoolMXBeans().filter(::isTenuredHeap)
        assertTrue(tenured.isNotEmpty(), "this JVM exposes no tenured heap pool")

        val installed = installDesktopMemoryPressure(monitor)
        tenured.forEach { pool ->
            val max = HeapPressure.effectiveMax(pool.usage.max, pool.usage.committed)
            assertEquals((max * HeapPressure.MODERATE_RATIO).toLong(), pool.collectionUsageThreshold, pool.name)
        }
        installed.close()

        tenured.forEach { assertEquals(0L, it.collectionUsageThreshold, it.name) }
    }
}
