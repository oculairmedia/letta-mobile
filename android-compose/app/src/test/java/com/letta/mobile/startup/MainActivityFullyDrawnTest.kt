package com.letta.mobile.startup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val source = File(MAIN_ACTIVITY).readLines().joinToString("\n") { it.substringBefore("//") }
        val calls = Regex("""(?<![\w.])(this\.|this@MainActivity\.)?reportFullyDrawn\(\)""").findAll(source).map { it.value }.toList()
        assertFalse("MainActivity.kt calls reportFullyDrawn() directly: $calls", calls.isNotEmpty())
    }

    @Test
    fun theWorkspaceReportsBeforeItsWaitingPageReturns() {
        val source = File(WORKSPACE).readText()
        val report = source.indexOf("ReportFirstUsableContent(")
        val waitingReturn = source.indexOf("if (!shell.sourceReady) return AndroidShellPage")
        assertTrue("AndroidShellWorkspace never calls ReportFirstUsableContent", report >= 0)
        assertTrue("the waiting-page early return precedes the report, which would then leave and re-enter the composition", report < waitingReturn)
    }

    private companion object {
        const val WORKSPACE = "src/main/java/com/letta/mobile/ui/navigation/AdaptiveScaffold.kt"

        /** Gradle runs the module's unit tests from the module directory. */
        const val MAIN_ACTIVITY = "src/main/java/com/letta/mobile/MainActivity.kt"
    }
}
