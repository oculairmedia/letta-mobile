@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.context

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.context.ContextWindowUsage
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The meter moves to changed numbers over time, and takes them at once with reduced motion. */
class AnimatedContextUsageTest {
    private val before = ContextWindowUsage.total(usedTokens = 40_000, windowTokens = 200_000)
    private val after = ContextWindowUsage.total(usedTokens = 80_000, windowTokens = 200_000)

    @Test
    fun aChangedReadingTravelsInsteadOfSnapping() = runComposeUiTest {
        var target by mutableStateOf(before)
        var shown: ContextWindowUsage? = null
        mainClock.autoAdvance = false
        setContent { shown = animatedContextUsage(target) }
        mainClock.advanceTimeByFrame()

        target = after
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val midway = requireNotNull(shown).usedTokens
        assertTrue(midway in 40_000 until 80_000, "mid-travel, not snapped: $midway")

        mainClock.advanceTimeBy(ANIMATION_BUDGET_MS)
        assertEquals(after, shown)
    }

    @Test
    fun withReducedMotionAChangedReadingIsTakenAtOnce() = runComposeUiTest {
        var target by mutableStateOf(before)
        var shown: ContextWindowUsage? = null
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) { shown = animatedContextUsage(target) }
        }
        mainClock.advanceTimeByFrame()

        target = after
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()

        assertEquals(after, shown)
    }

    private companion object {
        const val ANIMATION_BUDGET_MS = 2_000L
    }
}
