package com.letta.mobile.startup

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.letta.mobile.ui.shell.ReportFirstUsableContent
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-1hzkq: the activity's real FullyDrawnReporter (the one Activity.reportFullyDrawn() feeds, read back
 * through its own `isFullyDrawnReported`) stays unreported until the shell's first screen is usable, reports once
 * after it, and still reports after the timeout when the screen never becomes usable (an offline backend).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class ActivityFirstUsableTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var usable by mutableStateOf(false)

    private fun reported() = compose.activity.fullyDrawnReporter.isFullyDrawnReported

    private fun show(timeout: kotlin.time.Duration) = compose.setContent { ReportFirstUsableContent(usable, timeout) }

    @Test
    fun notReportedBeforeUsableThenReportedOnceAfter() {
        var reports = 0
        compose.activity.fullyDrawnReporter.addOnReportDrawnListener { reports++ }
        show(TIMEOUT)
        compose.waitForIdle()
        assertFalse("reported before the screen was usable", reported())
        assertEquals(0, reports)
        compose.mainClock.advanceTimeBy(TIMEOUT.inWholeMilliseconds / 2)
        compose.waitForIdle()
        assertFalse("reported before usable and before the timeout", reported())

        usable = true
        compose.waitForIdle()
        assertTrue("not reported once the screen was usable", reported())
        assertEquals(1, reports)

        compose.mainClock.advanceTimeBy(TIMEOUT.inWholeMilliseconds * 2)
        compose.waitForIdle()
        assertEquals("reported again after the timeout", 1, reports)
    }

    @Test
    fun reportedAtTheTimeoutWhenNeverUsable() {
        show(TIMEOUT)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(TIMEOUT.inWholeMilliseconds - 1)
        compose.waitForIdle()
        assertFalse("reported before the timeout", reported())
        compose.mainClock.advanceTimeBy(2)
        compose.waitForIdle()
        assertTrue("never reported although the content never loaded", reported())
    }

    @Test
    fun reportedAtOnceWhenUsableFromTheFirstFrame() {
        usable = true
        show(TIMEOUT)
        compose.waitForIdle()
        assertTrue(reported())
    }

    private companion object {
        val TIMEOUT = 8.seconds
    }
}
