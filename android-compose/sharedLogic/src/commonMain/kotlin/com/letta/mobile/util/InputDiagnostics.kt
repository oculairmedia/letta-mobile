package com.letta.mobile.util

/**
 * letta-mobile-erx7m: switch for the touch-stall diagnostics (Telemetry tag `Input`). Debug
 * builds turn it on at startup; release builds never do, so the feature-module gesture probes
 * cost a single flag read.
 */
object InputDiagnostics {
    val enabled = TelemetryFlag(false)
}
