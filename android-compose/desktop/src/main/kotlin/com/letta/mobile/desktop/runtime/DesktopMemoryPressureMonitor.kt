package com.letta.mobile.desktop.runtime

import com.letta.mobile.data.platform.HeapPressure
import com.letta.mobile.data.platform.HeapPressureGate
import com.letta.mobile.data.platform.MemoryPressure
import com.letta.mobile.data.platform.MemoryPressureLevel
import java.lang.management.ManagementFactory
import java.lang.management.MemoryNotificationInfo
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.lang.management.MemoryUsage
import javax.management.Notification
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reports heap pressure from the tenured pool's usage measured right after a collection, which is
 * live data. Raw heap usage counts uncollected garbage and reads as nearly full before every GC.
 */
internal class DesktopMemoryPressureMonitor(
    private val gate: HeapPressureGate = HeapPressureGate(floorWindow = Duration.ZERO, repeatAfter = 30.seconds),
    private val emit: (MemoryPressureLevel) -> Unit = MemoryPressure::emit,
) {
    fun onCollectionUsage(usage: MemoryUsage) {
        gate.offer(usage.used, HeapPressure.effectiveMax(usage.max, usage.committed))?.let(emit)
    }
}

/**
 * Arms a collection-usage threshold at the MODERATE watermark on each tenured heap pool; the JVM
 * then notifies after every collection that leaves the pool above it. Closing disarms them.
 */
internal fun installDesktopMemoryPressure(
    monitor: DesktopMemoryPressureMonitor = DesktopMemoryPressureMonitor(),
): AutoCloseable {
    val pools = ManagementFactory.getMemoryPoolMXBeans().filter(::isTenuredHeap)
    pools.forEach { pool ->
        val max = HeapPressure.effectiveMax(pool.usage.max, pool.usage.committed)
        pool.collectionUsageThreshold = (max * HeapPressure.MODERATE_RATIO).toLong()
    }
    val emitter = ManagementFactory.getMemoryMXBean() as NotificationEmitter
    val listener = NotificationListener { notification, _ -> collectionUsageOf(notification)?.let(monitor::onCollectionUsage) }
    emitter.addNotificationListener(listener, null, null)
    return AutoCloseable {
        emitter.removeNotificationListener(listener)
        pools.forEach { it.collectionUsageThreshold = 0L }
    }
}

/** Old-generation pools: heap pools that take both a usage and a collection-usage threshold. */
internal fun isTenuredHeap(pool: MemoryPoolMXBean): Boolean =
    pool.type == MemoryType.HEAP && pool.isUsageThresholdSupported && pool.isCollectionUsageThresholdSupported

private fun collectionUsageOf(notification: Notification): MemoryUsage? {
    if (notification.type != MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED) return null
    val data = notification.userData as? CompositeData ?: return null
    return MemoryNotificationInfo.from(data).usage
}
