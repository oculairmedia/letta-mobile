package com.letta.mobile.util

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

class TelemetryRecorderTest {
    @Test
    fun recordsNewestFirstAndEvictsOnlyOldestBeyondCapacity() {
        val recorder = TelemetryRecorder()
        repeat(1001) { recorder.record(event(it)) }

        assertEquals((1000 downTo 1).map { it.toString() }, recorder.snapshot().map { it.name })
        assertEquals(recorder.snapshot(), recorder.events.value)
        assertFalse(recorder.events is MutableStateFlow<List<Telemetry.Event>>)
    }

    @Test
    fun lifecyclesHaveIndependentHistoryAndClearDoesNotMutateSnapshots() {
        val first = TelemetryRecorder()
        val second = TelemetryRecorder()
        first.record(event(1))
        second.record(event(2))
        val snapshot = first.snapshot()

        first.clear()

        assertTrue(first.events.value.isEmpty())
        assertEquals(listOf("1"), snapshot.map { it.name })
        assertEquals(listOf("2"), second.snapshot().map { it.name })
    }

    @Test
    fun recordingPreservesCompleteEventAndDiagnosticValues() {
        val recorder = TelemetryRecorder()
        val failure = IllegalStateException("failure")
        val original = event(1).copy(
            durationMs = 12L,
            attrs = mapOf(
                "null" attr TelemetryValue.Null,
                "text" attr "value",
                "count" attr 3,
                "duration" attr 4L,
                "flag" attr true,
            ),
            level = Telemetry.Level.ERROR,
            throwable = failure,
        )

        recorder.record(original)

        assertEquals(original, recorder.snapshot().single())
        assertEquals(original.toLine(), recorder.snapshot().single().toLine())
    }

    @Test
    fun concurrentWritersDoNotLoseEventsWithinCapacity() {
        val recorder = TelemetryRecorder()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val writes = (0 until 1000).map { index -> executor.submit { recorder.record(event(index)) } }
            writes.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1000, recorder.snapshot().size)
            assertEquals((0 until 1000).map { it.toString() }.toSet(), recorder.snapshot().map { it.name }.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun event(index: Int) = Telemetry.Event(
        timestampMs = index.toLong(),
        tag = "RecorderTest",
        name = index.toString(),
        durationMs = null,
        attrs = emptyMap(),
        level = Telemetry.Level.DEBUG,
    )
}
