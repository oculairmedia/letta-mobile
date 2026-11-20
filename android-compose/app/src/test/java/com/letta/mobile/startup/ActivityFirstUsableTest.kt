package com.letta.mobile.startup

import androidx.activity.FullyDrawnReporter
import androidx.activity.FullyDrawnReporterOwner
import androidx.activity.compose.LocalFullyDrawnReporterOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.letta.mobile.ui.shell.FirstUsableReason
import com.letta.mobile.ui.shell.ReportFirstUsableContent
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-1hzkq: the shell's first-usable signal drives a real [FullyDrawnReporter] (the class behind
 * `Activity.reportFullyDrawn()`, through the real `ReportDrawnWhen`): nothing is reported before the first
 * screen is usable, exactly one report follows it, and one still follows the timeout when it never becomes
 * usable (an offline backend). The reporter's executor runs inline and its report callback counts, because the
 * activity's own executor waits for a window draw Robolectric does not run; everything between is real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("integration")
class ActivityFirstUsableTest {
    @get:Rule val compose = createComposeRule()
    private var usable by mutableStateOf(false)
    private var reports = 0
    private val reasons = mutableListOf<FirstUsableReason>()
    private val reporter = FullyDrawnReporter(Runnable::run) { reports++ }
    private val owner = object : FullyDrawnReporterOwner { override val fullyDrawnReporter = reporter }

    private fun show() {
        // waitForIdle would otherwise run the timeout's delay itself; the test drives the clock.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalFullyDrawnReporterOwner provides owner) {
                ReportFirstUsableContent(usable, TIMEOUT) { reasons += it }
            }
        }
    }

    /** The test thread's state write reaches the reporter's snapshot observer once the apply notification is sent. */
    private fun advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }

    @Test
    fun notReportedBeforeUsableThenReportedOnceAfter() {
        show()
        advance(TIMEOUT.inWholeMilliseconds / 2)
        assertEquals("reported before the screen was usable", 0, reports)

        usable = true
        advance(FRAME_MS)
        assertEquals("not reported once the screen was usable", 1, reports)

        advance(TIMEOUT.inWholeMilliseconds * 2)
        assertEquals("reported again after the timeout", 1, reports)
        assertEquals("the host is told the content was the reason, once", listOf(FirstUsableReason.Content), reasons)
    }

    @Test
    fun reportedAtTheTimeoutWhenNeverUsable() {
        show()
        advance(TIMEOUT.inWholeMilliseconds - 1)
        assertEquals("reported before the timeout", 0, reports)
        advance(2)
        assertEquals("never reported although the content never loaded", 1, reports)
        assertEquals("the host is told the timeout was the reason, once", listOf(FirstUsableReason.Timeout), reasons)
    }

    @Test
    fun reportedAtOnceWhenUsableFromTheFirstFrame() {
        usable = true
        show()
        advance(FRAME_MS)
        assertEquals(1, reports)
    }

    private companion object {
        val TIMEOUT = 8.seconds
        const val FRAME_MS = 32L
    }
}
