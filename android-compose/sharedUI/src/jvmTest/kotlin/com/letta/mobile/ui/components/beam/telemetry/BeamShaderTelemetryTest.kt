package com.letta.mobile.ui.components.beam.telemetry

import com.letta.mobile.ui.components.beam.BeamShaderDiagnostics
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BeamShaderTelemetryTest {
    @Test
    fun shaderFailureReachesTelemetry() {
        val previous = BeamShaderDiagnostics.reportFailure
        val recorder = TelemetryRecorder()
        Telemetry.installRecorder(recorder).use {
            try {
                BeamShaderDiagnostics.reportFailure = {}
                installBeamShaderTelemetry()
                BeamShaderDiagnostics.reportFailure(IllegalStateException("shader failure"))
                val event = assertNotNull(Telemetry.events.value.lastOrNull { it.tag == "BorderBeam" })
                assertEquals("shader.compileFailed", event.name)
                assertEquals(Telemetry.Level.WARN, event.level)
            } finally {
                BeamShaderDiagnostics.reportFailure = previous
            }
        }
    }
}
