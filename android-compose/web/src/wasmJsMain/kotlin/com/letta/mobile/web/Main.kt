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
    kotlinx.browser.window.addEventListener("pagehide", { telemetryRecording.close() })
    val body = document.body ?: return
    ComposeViewport(body) {
        LettaWebApp(telemetryRecorder)
    }
}
