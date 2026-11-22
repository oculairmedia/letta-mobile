@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.gesture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared swipe-away: let go past its threshold the item is dismissed, short of it it springs back
 * to rest, a screen reader can dismiss it with the accessibility action, and under reduced motion a
 * swipe still dismisses (it only skips the slide).
 */
class SwipeAwayUiTest {
    private var dismissed = 0

    private fun ComposeUiTest.showItem(spec: SwipeAwaySpec, reduced: Boolean = false, enabled: Boolean = true) = setContent {
        CompositionLocalProvider(LocalLettaReducedMotion provides reduced) {
            Box(Modifier.size(ITEM_WIDTH, ITEM_HEIGHT)) {
                Box(Modifier.size(ITEM_WIDTH, ITEM_HEIGHT).testTag(ITEM).swipeAway(spec, enabled) { dismissed++ })
            }
        }
    }

    @Test
    fun aSwipePastTheThresholdDismisses() = runComposeUiTest {
        showItem(byDistance)
        onNodeWithTag(ITEM).performTouchInput { swipeLeft() }
        waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun aSwipeShortOfTheThresholdSpringsBack() = runComposeUiTest {
        showItem(byDistance)
        val rest = onNodeWithTag(ITEM).getBoundsInRoot().left
        onNodeWithTag(ITEM).performTouchInput { swipeRight(startX = centerX, endX = centerX + SHORT_PX) }
        waitForIdle()
        assertEquals(0, dismissed, "a short swipe must not dismiss")
        assertEquals(rest, onNodeWithTag(ITEM).getBoundsInRoot().left, "the item did not spring back to rest")
    }

    @Test
    fun theAccessibilityDismissActionDismisses() = runComposeUiTest {
        showItem(byDistance, enabled = false)
        onNodeWithTag(ITEM).performSemanticsAction(SemanticsActions.Dismiss)
        waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun underReducedMotionASwipeStillDismisses() = runComposeUiTest {
        showItem(byDistance, reduced = true)
        onNodeWithTag(ITEM).performTouchInput { swipeRight() }
        waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun aVerticalSwipeByShareDismissesInPlace() = runComposeUiTest {
        showItem(SwipeAwaySpec(SwipeAwayThreshold.Fraction(HALF), inPlace, axis = SwipeAwayAxis.Vertical))
        onNodeWithTag(ITEM).performTouchInput { swipeDown() }
        waitForIdle()
        assertTrue(dismissed == 1, "a full downward swipe dismisses")
    }

    @Test
    fun aSwipeAcrossTheAxisDoesNothing() = runComposeUiTest {
        showItem(SwipeAwaySpec(SwipeAwayThreshold.Fraction(HALF), inPlace, axis = SwipeAwayAxis.Vertical))
        onNodeWithTag(ITEM).performTouchInput { swipeLeft() }
        waitForIdle()
        assertFalse(dismissed > 0, "a sideways swipe dismissed a vertical swipe-away")
    }

    private companion object {
        const val ITEM = "swipe-away-item"
        const val SHORT_PX = 20f
        const val HALF = 0.5f
        val ITEM_WIDTH = 300.dp
        val ITEM_HEIGHT = 120.dp
        val offEdge = SwipeAwayRelease(SwipeAwayExit.OffEdge, exitMillis = 130, settleMillis = 130)
        val inPlace = SwipeAwayRelease(SwipeAwayExit.InPlace, exitMillis = 130, settleMillis = 220)
        val byDistance = SwipeAwaySpec(SwipeAwayThreshold.Distance(96.dp), offEdge)
    }
}
