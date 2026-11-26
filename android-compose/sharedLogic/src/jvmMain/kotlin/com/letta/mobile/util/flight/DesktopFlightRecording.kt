package com.letta.mobile.util.flight

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import java.io.File

/**
 * The desktop app's telemetry for its lifetime: a fresh [TelemetryRecorder] history plus the flight record in
 * `~/.letta-mobile/logs/flight-record.log`, beside the local runtime's log. Closing the result stops both.
 */
fun installDesktopTelemetry(build: String): AutoCloseable {
    val recorder = TelemetryRecorder()
    val history = Telemetry.installRecorder(recorder)
    val flight = installFileFlightRecording(recorder, File(System.getProperty("user.home"), ".letta-mobile/logs"), build)
    return AutoCloseable {
        flight.close()
        history.close()
    }
}
