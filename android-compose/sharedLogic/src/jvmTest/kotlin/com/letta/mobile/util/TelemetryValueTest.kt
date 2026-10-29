package com.letta.mobile.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException

class TelemetryValueTest {
    @Test
    fun scalarKindsNullsDuplicateKeysAndRenderingArePreserved() = withRecorder { recorder ->
        val nullableText: String? = null
        Telemetry.event(
            "Values", "scalars",
            "null" attr nullableText,
            "text" attr "original",
            "text" attr "replacement",
            "int" attr 3,
            "long" attr 4L,
            "double" attr 1.5,
            "float" attr 2.5f,
            "short" attr 6.toShort(),
            "byte" attr 7.toByte(),
            "flag" attr true,
        )
        val event = recorder.snapshot().single()
        assertEquals(TelemetryValue.Null, event.attrs["null"])
        assertEquals(TelemetryValue.Text("replacement"), event.attrs["text"])
        assertEquals(TelemetryValue.Numeric(3), event.attrs["int"])
        assertEquals(TelemetryValue.Numeric(4L), event.attrs["long"])
        assertEquals(TelemetryValue.Numeric(1.5), event.attrs["double"])
        assertEquals(TelemetryValue.Numeric(2.5f), event.attrs["float"])
        assertEquals(TelemetryValue.Numeric(6.toShort()), event.attrs["short"])
        assertEquals(TelemetryValue.Numeric(7.toByte()), event.attrs["byte"])
        assertEquals(TelemetryValue.Flag(true), event.attrs["flag"])
        assertTrue(event.toLine().contains("null=null, text=replacement, int=3, long=4"))
        assertTrue(Telemetry.exportText().contains(event.toLine()))
    }

    @Test
    fun numericListsPreserveStructurePrimitiveKindsAndBracketedRendering() = withRecorder { recorder ->
        Telemetry.event(
            "Values", "numeric-lists",
            "ids" attr listOf(1L, 2L),
            "mixed" attr listOf<Number>(3, 4L, 5.5f),
            "empty" attr emptyList<Number>(),
        )
        val event = recorder.snapshot().single()
        val ids = assertIs<TelemetryValue.NumericList>(event.attrs["ids"]).value
        assertEquals(listOf(1L, 2L), ids)
        assertTrue(ids.all { it is Long })
        val mixed = assertIs<TelemetryValue.NumericList>(event.attrs["mixed"]).value
        assertIs<Int>(mixed[0])
        assertIs<Long>(mixed[1])
        assertIs<Float>(mixed[2])
        assertTrue(assertIs<TelemetryValue.NumericList>(event.attrs["empty"]).value.isEmpty())
        assertTrue(event.toLine().contains("ids=[1, 2], mixed=[3, 4, 5.5], empty=[]"))
    }

    @Test
    fun errorsMeasuredFailuresAndTimerCancellationKeepDiagnosticsAndRethrow() = withRecorder { recorder ->
        val failure = IllegalStateException("broken")
        Telemetry.error("Values", "error", failure, "count" attr 3)
        assertEquals(TelemetryValue.Text("IllegalStateException"), recorder.snapshot().first().attrs["errorClass"])
        assertEquals(TelemetryValue.Text("broken"), recorder.snapshot().first().attrs["errorMessage"])
        assertFailsWith<CancellationException> {
            Telemetry.measure("Values", "measured", "count" attr 3) {
                throw CancellationException("cancelled")
            }
        }
        val timer = Telemetry.startTimer("Values", "timer")
        timer.stopError(CancellationException("cancelled"), "count" attr 4L)
        assertEquals(listOf("timer:failed", "measured:failed", "error"), recorder.snapshot().map { it.name })
        assertTrue(recorder.snapshot().first().attrs["durationMs"] is TelemetryValue.Numeric)
        assertEquals(TelemetryValue.Numeric(4L), recorder.snapshot().first().attrs["count"])
    }

    private fun withRecorder(block: (TelemetryRecorder) -> Unit) {
        val recorder = TelemetryRecorder()
        val enabled = Telemetry.enabled.get()
        val logcat = Telemetry.logcatEnabled.get()
        Telemetry.installRecorder(recorder).use {
            try {
                Telemetry.enabled.set(true)
                Telemetry.logcatEnabled.set(false)
                block(recorder)
            } finally {
                Telemetry.enabled.set(enabled)
                Telemetry.logcatEnabled.set(logcat)
            }
        }
    }
}
