package com.letta.mobile.data.platform

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class MemoryPressureHubTest {
    @Test
    fun onlyCriticalSignalsTrimResidentPages() = runTest {
        val hub = MemoryPressureHub()
        val trims = mutableListOf<Unit>()
        val levels = mutableListOf<MemoryPressureLevel>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(dispatcher) { hub.criticalTrims().toList(trims) }
        backgroundScope.launch(dispatcher) { hub.levels.toList(levels) }

        hub.emit(MemoryPressureLevel.MODERATE)
        hub.emit(MemoryPressureLevel.CRITICAL)
        hub.emit(MemoryPressureLevel.MODERATE)

        assertEquals(1, trims.size)
        assertEquals(
            listOf(MemoryPressureLevel.MODERATE, MemoryPressureLevel.CRITICAL, MemoryPressureLevel.MODERATE),
            levels,
        )
    }
}

class HeapPressureGateTest {
    private val clock = TestTimeSource()

    private fun gate(floorWindow: Duration = Duration.ZERO) =
        HeapPressureGate(floorWindow = floorWindow, repeatAfter = 30.seconds, timeSource = clock)

    @Test
    fun liveReadingsReportARiseAtOnce() {
        val gate = gate()

        assertEquals(null, gate.offer(usedBytes = 60, maxBytes = 100))
        assertEquals(MemoryPressureLevel.MODERATE, gate.offer(usedBytes = 80, maxBytes = 100))
        assertEquals(MemoryPressureLevel.CRITICAL, gate.offer(usedBytes = 95, maxBytes = 100))
    }

    @Test
    fun aHeldLevelIsReportedAgainOnlyAfterTheRepeatInterval() {
        val gate = gate()
        assertEquals(MemoryPressureLevel.CRITICAL, gate.offer(95, 100))

        clock += 10.seconds
        assertEquals(null, gate.offer(96, 100), "sustained pressure must not drop caches on every reading")
        clock += 20.seconds
        assertEquals(MemoryPressureLevel.CRITICAL, gate.offer(96, 100))
    }

    @Test
    fun pressureThatClearsIsReportedAfreshWhenItReturns() {
        val gate = gate()
        assertEquals(MemoryPressureLevel.MODERATE, gate.offer(80, 100))
        clock += 1.seconds
        assertEquals(null, gate.offer(40, 100))
        clock += 1.seconds
        assertEquals(MemoryPressureLevel.MODERATE, gate.offer(80, 100))
    }

    @Test
    fun garbageBetweenCollectionsIsNotPressure() {
        // Raw usage climbs to nearly full before each collection and falls back after it.
        val gate = gate(floorWindow = 30.seconds)
        val sawtooth = listOf(40, 70, 95, 45, 75, 97, 42, 80, 96, 44)
        val reported = sawtooth.mapNotNull { used ->
            gate.offer(used.toLong(), 100).also { clock += 5.seconds }
        }
        assertEquals(emptyList(), reported)
    }

    @Test
    fun retainedMemoryIsReportedOnceAWholeWindowStaysHigh() {
        val gate = gate(floorWindow = 30.seconds)
        val readings = (0..6).map { step ->
            gate.offer(if (step % 2 == 0) 92 else 99, 100).also { clock += 5.seconds }
        }
        assertEquals(List(6) { null } + MemoryPressureLevel.CRITICAL, readings)
    }

    @Test
    fun unusableReadingsAreIgnored() {
        val gate = gate()
        assertEquals(null, gate.offer(usedBytes = 10, maxBytes = 0))
        assertEquals(null, gate.offer(usedBytes = -1, maxBytes = 100))
    }

    @Test
    fun effectiveMaxFallsBackToCommitted() {
        assertEquals(80, HeapPressure.effectiveMax(-1, 80))
        assertEquals(200, HeapPressure.effectiveMax(200, 80))
    }
}
