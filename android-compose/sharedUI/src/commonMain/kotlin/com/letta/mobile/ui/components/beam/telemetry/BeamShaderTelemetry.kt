package com.letta.mobile.ui.components.beam.telemetry

import com.letta.mobile.ui.components.beam.BeamShaderDiagnostics
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr

/** Installs shader failure reporting before composing beams. */
fun installBeamShaderTelemetry() {
    BeamShaderDiagnostics.reportFailure = { failure ->
        Telemetry.event(
            "BorderBeam", "shader.compileFailed",
            "error" attr (failure.message ?: failure::class.simpleName.orEmpty()),
            level = Telemetry.Level.WARN,
        )
    }
}
