package com.letta.mobile.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val telemetryRecorder = com.letta.mobile.util.TelemetryRecorder()
    val telemetryRecording = com.letta.mobile.util.Telemetry.installRecorder(telemetryRecorder)
    kotlinx.browser.window.addEventListener("unload", { telemetryRecording.close() })
    val body = document.body ?: return
    ComposeViewport(body) {
        LettaWebApp()
    }
}
