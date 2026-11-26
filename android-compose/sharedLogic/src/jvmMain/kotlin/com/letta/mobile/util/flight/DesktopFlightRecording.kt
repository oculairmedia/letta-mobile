package com.letta.mobile.util.flight

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * The desktop app's telemetry for its lifetime: a fresh [TelemetryRecorder] history plus the flight record in
 * `~/.letta-mobile/logs/flight-record.log`, beside the local runtime's log, written from [scope] (the scope `main` owns for
 * the process). Closing the result stops both; the record's writer then writes what is waiting and ends.
 */
fun installDesktopTelemetry(build: String, scope: CoroutineScope): AutoCloseable {
    val recorder = TelemetryRecorder()
    val history = Telemetry.installRecorder(recorder)
    val flight = installFileFlightRecording(recorder, File(System.getProperty("user.home"), ".letta-mobile/logs"), build, scope)
    return AutoCloseable {
        flight.close()
        history.close()
    }
}
