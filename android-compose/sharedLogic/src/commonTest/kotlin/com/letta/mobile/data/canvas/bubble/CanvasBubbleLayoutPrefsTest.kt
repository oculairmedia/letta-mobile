package com.letta.mobile.data.canvas.bubble

import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout.Companion.COMPOSER_FULL_DP
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout.Companion.COMPOSER_MIN_DP
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout.Companion.MASCOT_MAX_DP
import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout.Companion.MASCOT_MIN_DP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasBubbleLayoutPrefsTest {
    private val desktop = CanvasBubbleLayout.Desktop

    @Test
    fun platformDefaultsKeepThePhoneAsItWasAndShrinkTheDesktop() {
        assertEquals(120f, CanvasBubbleLayout.Touch.mascotSizeDp)
        assertNull(CanvasBubbleLayout.Touch.composerWidthDp)
        assertEquals(96f, desktop.mascotSizeDp)
        assertEquals(640f, desktop.composerWidthDp)
    }

    @Test
    fun settersClampIntoRange() {
        val prefs = CanvasBubbleLayoutPrefs(desktop)
        prefs.setMascotSize(10f)
        assertEquals(MASCOT_MIN_DP, prefs.layout.value.mascotSizeDp)
        prefs.setMascotSize(900f)
        assertEquals(MASCOT_MAX_DP, prefs.layout.value.mascotSizeDp)
        prefs.setComposerWidth(100f)
        assertEquals(COMPOSER_MIN_DP, prefs.layout.value.composerWidthDp)
        prefs.setComposerWidth(5000f)
        assertEquals(COMPOSER_FULL_DP, prefs.layout.value.composerWidthDp)
        assertTrue(prefs.layout.value.composerFull)
    }

    @Test
    fun theMascotRangeIsLargerAtBothEnds() {
        // The old 48..200 range was too small at both ends; the bar may be narrower than before.
        assertEquals(72f, MASCOT_MIN_DP)
        assertEquals(400f, MASCOT_MAX_DP)
        assertTrue(COMPOSER_MIN_DP < 360f)
    }

    @Test
    fun theBarHeightClampsAndStartsAtOneLine() {
        val prefs = CanvasBubbleLayoutPrefs(desktop)
        assertNull(prefs.layout.value.composerHeightDp)
        assertFalse(prefs.layout.value.composerMultiLine)
        prefs.setComposerHeight(5f)
        assertEquals(CanvasBubbleLayout.COMPOSER_HEIGHT_MIN_DP, prefs.layout.value.composerHeightDp)
        assertFalse(prefs.layout.value.composerMultiLine)
        prefs.setComposerHeight(9000f)
        assertEquals(CanvasBubbleLayout.COMPOSER_HEIGHT_MAX_DP, prefs.layout.value.composerHeightDp)
        assertTrue(prefs.layout.value.composerMultiLine)
        prefs.reset()
        assertNull(prefs.layout.value.composerHeightDp)
    }

    @Test
    fun notANumberKeepsTheCurrentValue() {
        val prefs = CanvasBubbleLayoutPrefs(desktop)
        prefs.setMascotSize(Float.NaN)
        prefs.setComposerWidth(Float.POSITIVE_INFINITY)
        assertEquals(desktop, prefs.layout.value)
    }

    @Test
    fun stepsMoveTheMascotByANotchAndStopAtTheEnds() {
        val prefs = CanvasBubbleLayoutPrefs(desktop)
        prefs.stepMascot(2)
        assertEquals(96f + 2 * CanvasBubbleLayout.MASCOT_STEP_DP, prefs.layout.value.mascotSizeDp)
        prefs.stepMascot(-100)
        assertEquals(MASCOT_MIN_DP, prefs.layout.value.mascotSizeDp)
    }

    @Test
    fun resetReturnsToThePlatformDefaults() {
        val prefs = CanvasBubbleLayoutPrefs(desktop, initial = CanvasBubbleLayout(150f, 900f))
        assertEquals(150f, prefs.layout.value.mascotSizeDp)
        prefs.reset()
        assertEquals(desktop, prefs.layout.value)
    }

    @Test
    fun anOutOfRangeLoadedValueIsClampedOnArrival() {
        val prefs = CanvasBubbleLayoutPrefs(desktop, initial = CanvasBubbleLayout(1f, 1f))
        assertEquals(CanvasBubbleLayout(MASCOT_MIN_DP, COMPOSER_MIN_DP), prefs.layout.value)
    }

    @Test
    fun theCodecRoundTripsWhatThePersonSet() {
        val prefs = CanvasBubbleLayoutPrefs(desktop)
        prefs.setMascotSize(240f)
        prefs.setComposerWidth(820f)
        prefs.setComposerHeight(160f)
        val stored = CanvasBubbleLayoutCodec.encode(prefs.layout.value)
        val reloaded = CanvasBubbleLayoutPrefs(desktop, initial = CanvasBubbleLayoutCodec.decode(stored, desktop))
        assertEquals(CanvasBubbleLayout(240f, 820f, composerHeightDp = 160f), reloaded.layout.value)
    }

    @Test
    fun theCodecReadsMissingOrDamagedEntriesAsDefaults() {
        assertEquals(desktop, CanvasBubbleLayoutCodec.decode(emptyMap(), desktop))
        val damaged = mapOf(
            CanvasBubbleLayoutCodec.KEY_MASCOT_SIZE to "huge",
            CanvasBubbleLayoutCodec.KEY_COMPOSER_WIDTH to "9999",
        )
        assertEquals(CanvasBubbleLayout(96f, COMPOSER_FULL_DP), CanvasBubbleLayoutCodec.decode(damaged, desktop))
    }

    @Test
    fun theTouchLayoutKeepsFollowingThePanelThroughARoundTrip() {
        val stored = CanvasBubbleLayoutCodec.encode(CanvasBubbleLayout.Touch)
        assertFalse(CanvasBubbleLayoutCodec.KEY_COMPOSER_WIDTH in stored)
        assertEquals(CanvasBubbleLayout.Touch, CanvasBubbleLayoutCodec.decode(stored, CanvasBubbleLayout.Touch))
    }

    @Test
    fun anAutomaticHoldGrowsWithTheReplyAndIsCapped() {
        val auto = desktop.copy(replyHoldSeconds = null)
        assertEquals(4_000L, auto.replyHoldMillis(0))
        assertEquals(6_000L, auto.replyHoldMillis(50))
        assertEquals(CanvasBubbleLayout.REPLY_HOLD_AUTO_MAX_MS, auto.replyHoldMillis(10_000))
    }

    @Test
    fun aFixedHoldIsClampedAndAlwaysMeansUntilDismissed() {
        val prefs = CanvasBubbleLayoutPrefs(desktop)
        prefs.setReplyHold(1f)
        assertEquals(3_000L, prefs.layout.value.replyHoldMillis(500))
        prefs.setReplyHold(99f)
        assertTrue(prefs.layout.value.replyHoldsForever)
        assertNull(prefs.layout.value.replyHoldMillis(10))
        assertNull(CanvasBubbleLayout.Touch.replyHoldMillis(10))
    }

    @Test
    fun theCodecRoundTripsTheHoldIncludingAutomatic() {
        val fixed = CanvasBubbleLayout(80f, 700f, replyHoldSeconds = 12f)
        assertEquals(fixed, CanvasBubbleLayoutCodec.decode(CanvasBubbleLayoutCodec.encode(fixed), desktop))
        // An automatic hold stays automatic even where the platform's default is "always".
        val auto = CanvasBubbleLayout.Touch.copy(replyHoldSeconds = null)
        assertEquals(auto, CanvasBubbleLayoutCodec.decode(CanvasBubbleLayoutCodec.encode(auto), CanvasBubbleLayout.Touch))
    }
}
