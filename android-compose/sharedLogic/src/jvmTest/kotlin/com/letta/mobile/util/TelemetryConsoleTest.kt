package com.letta.mobile.util

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryConsoleTest {
    @Test
    fun fallbackConsoleOmitsDebugButRetainsDiagnosticsAndWarnings() {
        val originalOut = System.out
        val originalDelegate = Telemetry.delegate
        val originalEnabled = Telemetry.enabled.get()
        val originalLogcat = Telemetry.logcatEnabled.get()
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output))
            Telemetry.delegate = null
            Telemetry.enabled.set(true)
            Telemetry.logcatEnabled.set(true)
            Telemetry.event("ConsoleTest", "debug-marker", level = Telemetry.Level.DEBUG)
            Telemetry.event("ConsoleTest", "warning-marker", level = Telemetry.Level.WARN)
            Telemetry.event("ConsoleTest", "error-marker", level = Telemetry.Level.ERROR)
            val text = output.toString()
            assertFalse(text.contains("debug-marker"))
            assertTrue(text.contains("warning-marker"))
            assertTrue(text.contains("error-marker"))
            assertTrue(Telemetry.snapshot().any { it.name == "debug-marker" })
        } finally {
            System.setOut(originalOut)
            Telemetry.delegate = originalDelegate
            Telemetry.enabled.set(originalEnabled)
            Telemetry.logcatEnabled.set(originalLogcat)
            output.close()
        }
    }
}
