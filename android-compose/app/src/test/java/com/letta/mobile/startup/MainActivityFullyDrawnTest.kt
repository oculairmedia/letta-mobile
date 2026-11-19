package com.letta.mobile.startup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * letta-mobile-1hzkq: `Activity.reportFullyDrawn()` marks the first USABLE frame for Android vitals, Kotzilla and
 * Macrobenchmark's timeToFullDisplay. MainActivity.onCreate must therefore never call it itself (it used to, right
 * after setContent, so "Fully drawn" equalled the launch's TotalTime and hid the ~2.5 s first real frame): the shell
 * reports it through the FullyDrawnReporter once its content is there ([ActivityFirstUsableTest]).
 */
class MainActivityFullyDrawnTest {
    @Test
    fun mainActivityDoesNotReportFullyDrawnItself() {
        val source = File(MAIN_ACTIVITY).readText()
        val calls = Regex("""(?<![\w.])(this\.|this@MainActivity\.)?reportFullyDrawn\(\)""").findAll(source).map { it.value }.toList()
        assertFalse("MainActivity.kt calls reportFullyDrawn() directly: $calls", calls.isNotEmpty())
    }

    private companion object {
        /** Gradle runs the module's unit tests from the module directory. */
        const val MAIN_ACTIVITY = "src/main/java/com/letta/mobile/MainActivity.kt"
    }
}
