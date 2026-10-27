package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import com.letta.mobile.util.attr
import kotlin.test.Test
import kotlin.test.assertEquals

class IrohNodeTelemetryTest {
    @Test
    fun authenticationDiagnosticsPreserveOpaqueEndpointAndMalformedCorrelation() {
        val enabled = Telemetry.enabled.get()
        val logcat = Telemetry.logcatEnabled.get()
        val recorder = TelemetryRecorder()
        val recording = Telemetry.installRecorder(recorder)
        try {
            Telemetry.enabled.set(true)
            Telemetry.logcatEnabled.set(false)
            for (correlation in listOf("", "  ", "request-1")) {
                recordAuthenticationRequired(
                    "remoteEndpointId" attr "opaque-endpoint",
                    "requestId" attr correlation,
                )
                assertEquals(
                    mapOf(
                        "remoteEndpointId" attr "opaque-endpoint",
                        "requestId" attr correlation,
                        "reason" attr "missing_token",
                    ),
                    recorder.snapshot().first().attrs,
                )
            }
        } finally {
            recording.close()
            Telemetry.enabled.set(enabled)
            Telemetry.logcatEnabled.set(logcat)
        }
    }
}
