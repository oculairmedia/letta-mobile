package com.letta.mobile.data.platform

import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelinePressure
import com.letta.mobile.data.timeline.TimelineVisibleSpan
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryPressureTest {
    @BeforeTest
    fun reset() {
        MemoryPressure.resetForTest()
        TimelinePressure.resetForTest()
    }

    @AfterTest
    fun cleanup() {
        MemoryPressure.resetForTest()
        TimelinePressure.resetForTest()
    }

    @Test
    fun criticalTrimsOpenConversationToVisiblePlusOnePage() {
        val trimmed = mutableListOf<List<TimelineMessageId>>()
        val session = TimelinePressure.current().attach(
            TimelineScope("backend", "conversation"),
            pageSize = 5,
        ) { trimmed += it }
        session.reportResident((0 until 20).map { TimelineMessageId("m$it") })
        session.reportVisible(TimelineVisibleSpan(firstIndex = 8, count = 4))

        MemoryPressure.emit(MemoryPressureLevel.CRITICAL)

        val expected = (3 until 17).map { TimelineMessageId("m$it") }
        assertEquals(expected, session.residentIds())
        assertEquals(listOf(expected), trimmed)
    }

    @Test
    fun moderateDoesNotTrimTheTimeline() {
        val session = TimelinePressure.current().attach(
            TimelineScope("backend", "conversation"),
            pageSize = 5,
        ) { }
        val all = (0 until 20).map { TimelineMessageId("m$it") }
        session.reportResident(all)
        session.reportVisible(TimelineVisibleSpan(firstIndex = 8, count = 4))

        MemoryPressure.emit(MemoryPressureLevel.MODERATE)

        assertEquals(all, session.residentIds())
    }

    @Test
    fun criticalDropsImageCaches() {
        var dropped = 0
        MemoryPressure.current().caches.bind(MemoryCacheKind.Images) { dropped += 1 }
        MemoryPressure.emit(MemoryPressureLevel.CRITICAL)
        assertEquals(1, dropped)
    }

    @Test
    fun moderateDropsImagesAndLeavesCanvas() {
        var images = 0
        var canvas = 0
        val caches = MemoryPressure.current().caches
        caches.bind(MemoryCacheKind.Images) { images += 1 }
        caches.bind(MemoryCacheKind.Canvas) { canvas += 1 }
        MemoryPressure.emit(MemoryPressureLevel.MODERATE)
        assertEquals(1, images)
        assertEquals(0, canvas)
    }

    @Test
    fun criticalTightensRuntimeEventRetention() {
        val log = BoundedRuntimeEventLog(normalCap = 64)
        repeat(50) { log.append("e$it") }
        MemoryPressure.current().events.bind(log)
        MemoryPressure.emit(MemoryPressureLevel.CRITICAL)
        assertEquals(RuntimeEventPressure.CRITICAL_CAP, log.size())
        assertEquals((18 until 50).map { "e$it" }, log.snapshot())
    }

    @Test
    fun publishStatsIsVisibleForDiagnostics() {
        MemoryPressure.publishStats(HeapStats(usedBytes = 10, maxBytes = 100))
        assertEquals(HeapStats(10, 100), MemoryPressure.stats.value)
    }
}

class HeapPressureTest {
    @Test
    fun mapsRatios() {
        assertNull(HeapPressure.levelFor(74, 100))
        assertEquals(MemoryPressureLevel.MODERATE, HeapPressure.levelFor(75, 100))
        assertEquals(MemoryPressureLevel.CRITICAL, HeapPressure.levelFor(90, 100))
        assertNull(HeapPressure.levelFor(10, 0))
    }

    @Test
    fun effectiveMaxFallsBackToCommitted() {
        assertEquals(80, HeapPressure.effectiveMax(-1, 80))
        assertEquals(200, HeapPressure.effectiveMax(200, 80))
    }
}

class TimelineKeepRangeTest {
    @Test
    fun visiblePlusOnePageEachSide() {
        val range = TimelineVisibleSpan(8, 4).keepRange(pageSize = 5, residentCount = 20)
        assertEquals(3 until 17, range)
    }

    @Test
    fun unknownSpanKeepsANewestPage() {
        val range = TimelineVisibleSpan(0, 0).keepRange(pageSize = 5, residentCount = 20)
        assertEquals(0 until 6, range)
        assertTrue(range.count() <= 6)
    }
}
