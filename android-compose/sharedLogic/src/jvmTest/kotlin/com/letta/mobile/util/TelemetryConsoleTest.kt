package com.letta.mobile.util

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryConsoleTest {
    @Test
    fun fallbackConsoleOmitsDebugButRetainsDiagnosticsAndWarnings() {
        withTelemetryCapture { output ->
            Telemetry.event("ConsoleTest", "default-info")
            Telemetry.event("ConsoleTest", "debug-marker", level = Telemetry.Level.DEBUG)
            Telemetry.event("ConsoleTest", "warning-marker", level = Telemetry.Level.WARN)
            Telemetry.event("ConsoleTest", "error-marker", level = Telemetry.Level.ERROR)

            val text = output.toString()
            assertTrue(text.contains("[INFO] Telemetry/ConsoleTest: default-info"))
            assertFalse(text.contains("debug-marker"))
            assertTrue(text.contains("warning-marker"))
            assertTrue(text.contains("error-marker"))
            assertTrue(Telemetry.snapshot().any { it.name == "debug-marker" })
        }
    }

    @Test
    fun delegateReceivesDebugAndConsoleRemainsSilent() {
        withTelemetryCapture { output ->
            val delegate = RecordingTelemetryDelegate()
            Telemetry.delegate = delegate.instance

            Telemetry.event("ConsoleTest", "debug-marker", level = Telemetry.Level.DEBUG)

            assertEquals(1, delegate.events.size)
            assertEquals(
                RecordedEvent(Telemetry.Level.DEBUG, "Telemetry/ConsoleTest", "debug-marker", null),
                delegate.events.single(),
            )
            assertFalse(output.toString().contains("debug-marker"))
            assertTrue(Telemetry.snapshot().any { it.name == "debug-marker" })
        }
    }

    @Test
    fun debugRemainsInRingWithDelegateWhenLogcatDisabled() {
        withTelemetryCapture { output ->
            val delegate = RecordingTelemetryDelegate()
            Telemetry.delegate = delegate.instance
            Telemetry.logcatEnabled.set(false)

            Telemetry.event("ConsoleTest", "debug-marker", level = Telemetry.Level.DEBUG)

            assertTrue(delegate.events.isEmpty())
            assertFalse(output.toString().contains("debug-marker"))
            assertTrue(Telemetry.snapshot().any { it.name == "debug-marker" })
        }
    }

    @Test
    fun debugRemainsInRingWithoutDelegateWhenLogcatDisabled() {
        withTelemetryCapture { output ->
            Telemetry.logcatEnabled.set(false)

            Telemetry.event("ConsoleTest", "debug-marker", level = Telemetry.Level.DEBUG)

            assertTrue(output.toString().isEmpty())
            assertTrue(Telemetry.snapshot().any { it.name == "debug-marker" })
        }
    }

    private fun withTelemetryCapture(block: (ByteArrayOutputStream) -> Unit) {
        val originalOut = System.out
        val originalDelegate = Telemetry.delegate
        val originalEnabled = Telemetry.enabled.get()
        val originalLogcat = Telemetry.logcatEnabled.get()
        val output = ByteArrayOutputStream()
        val capturedOut = PrintStream(output)
        try {
            System.setOut(capturedOut)
            Telemetry.delegate = null
            Telemetry.enabled.set(true)
            Telemetry.logcatEnabled.set(true)
            Telemetry.clear()
            block(output)
        } finally {
            System.setOut(originalOut)
            Telemetry.delegate = originalDelegate
            Telemetry.enabled.set(originalEnabled)
            Telemetry.logcatEnabled.set(originalLogcat)
            Telemetry.clear()
            capturedOut.close()
        }
    }

    private data class RecordedEvent(
        val level: Telemetry.Level,
        val tag: String,
        val body: String,
        val throwable: Throwable?,
    )

    private class RecordingTelemetryDelegate {
        val events = mutableListOf<RecordedEvent>()
        // Only logging is observed here; tracing methods remain inert without a native platform delegate.
        val instance: TelemetryDelegate = Proxy.newProxyInstance(
            TelemetryDelegate::class.java.classLoader,
            arrayOf(TelemetryDelegate::class.java),
        ) { _, method, args ->
            if (method.name == "logToLogcat") {
                events += RecordedEvent(
                    args[0] as Telemetry.Level, args[1] as String, args[2] as String, args[3] as Throwable?,
                )
            }
            if (method.returnType == Boolean::class.javaPrimitiveType) false else null
        } as TelemetryDelegate
    }
}
