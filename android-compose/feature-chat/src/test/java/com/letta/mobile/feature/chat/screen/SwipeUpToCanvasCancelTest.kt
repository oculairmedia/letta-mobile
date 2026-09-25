package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-erx7m: an ACTION_CANCEL mid-swipe (window focus loss, a system gesture taking
 * the stream) reaches the detector as a release whose change Compose has already consumed. It
 * used to be read as an ordinary release and opened the canvas; it must abandon instead, and the
 * detector must still recognise the next gesture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class SwipeUpToCanvasCancelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var triggers = 0

    private fun setSurface() {
        composeRule.setContent {
            Box(
                Modifier
                    .size(300.dp)
                    .testTag(SURFACE)
                    .swipeUpToCanvas(enabled = true, onTrigger = { triggers++ }),
            )
        }
    }

    @Test
    fun releasePastThresholdCommits() {
        setSurface()
        composeRule.onNodeWithTag(SURFACE).performTouchInput {
            down(center)
            moveBy(Offset(0f, -SWIPE_PX))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(1, triggers)
    }

    @Test
    fun cancelPastThresholdDoesNotCommit() {
        setSurface()
        composeRule.onNodeWithTag(SURFACE).performTouchInput {
            down(center)
            moveBy(Offset(0f, -SWIPE_PX))
            cancel()
        }
        composeRule.waitForIdle()
        assertEquals(0, triggers)
    }

    @Test
    fun detectorRearmsAfterCancel() {
        setSurface()
        composeRule.onNodeWithTag(SURFACE).performTouchInput {
            down(center)
            moveBy(Offset(0f, -SWIPE_PX))
            cancel()
        }
        composeRule.onNodeWithTag(SURFACE).performTouchInput {
            down(center)
            moveBy(Offset(0f, -SWIPE_PX))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(1, triggers)
    }

    @Test
    fun consumedReleaseIsCancelledWhateverTheDragReached() {
        assertEquals(SwipeUpToCanvasOutcome.Cancelled, swipeUpReleaseOutcome(releaseConsumed = true, thresholdsMet = true))
        assertEquals(SwipeUpToCanvasOutcome.Committed, swipeUpReleaseOutcome(releaseConsumed = false, thresholdsMet = true))
        assertEquals(SwipeUpToCanvasOutcome.Released, swipeUpReleaseOutcome(releaseConsumed = false, thresholdsMet = false))
    }

    private companion object {
        const val SURFACE = "swipe-surface"
        const val SWIPE_PX = 200f
    }
}
