package com.letta.mobile.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import com.letta.mobile.ui.components.beam.telemetry.installBeamShaderTelemetry

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    installBeamShaderTelemetry()
    val telemetryRecorder = com.letta.mobile.util.TelemetryRecorder()
    val telemetryRecording = com.letta.mobile.util.Telemetry.installRecorder(telemetryRecorder)
    // The browser has no file system here: the flight record is in memory and ends with the page.
    val flightRecording = com.letta.mobile.util.flight.FlightRecording.installInMemory(telemetryRecorder, "web")
    kotlinx.browser.window.addEventListener("pagehide", {
        flightRecording.close()
        telemetryRecording.close()
    })
    val body = document.body ?: return
    ComposeViewport(body) {
        LettaWebApp()
    }
}
