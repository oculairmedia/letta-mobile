package com.letta.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCrashReporterTest {
    @Test
    fun userMessageHidesRawInternalClassNameFromNoClassDefFoundError() {
        // NoClassDefFoundError.message is the binary class name in internal form
        // (slashes + synthetic '$' segments) — meaningless to a user.
        val throwable = NoClassDefFoundError(
            "com/letta/mobile/desktop/memory/DesktopMemorySurfaceKt\$DesktopMemorySurface\$2\$1\$7\$1\$1",
        )

        val message = DesktopCrashReporter.userMessage(throwable)

        assertFalse(message.contains('/'), "user message should not leak an internal class path")
        assertFalse(message.contains('$'), "user message should not leak synthetic class segments")
        assertTrue(message.contains("restart", ignoreCase = true) || message.contains("reinstall", ignoreCase = true))
    }

    @Test
    fun userMessageTreatsAnyLinkageErrorAsCodeLoadingError() {
        val message = DesktopCrashReporter.userMessage(NoSuchMethodError("doThing"))

        assertTrue(message.contains("code-loading", ignoreCase = true))
    }

    @Test
    fun userMessagePassesThroughOrdinaryMessages() {
        val message = DesktopCrashReporter.userMessage(IllegalStateException("Backend offline"))

        assertEquals("Backend offline", message)
    }

    @Test
    fun userMessageFallsBackToTypeNameWhenBlank() {
        val message = DesktopCrashReporter.userMessage(RuntimeException())

        assertEquals("RuntimeException", message)
    }
}
