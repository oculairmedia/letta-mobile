package com.letta.mobile.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SnapSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class ChatMotionPolicyTest {

    @Test
    fun `reduced motion policy contract yields immediate snaps and no infinite animation`() {
        val policy = ChatMotionPolicy.Reduced

        assertTrue(policy.isReducedMotionEnabled)

        // 1. Running Cue (.4 shimmer)
        assertFalse(policy.runningCue.allowInfiniteAnimation)
        assertTrue(policy.runningCue.spec is SnapSpec)
        assertEquals(0.25f, policy.runningCue.staticAlpha, 0.001f)
        assertEquals(0.25f, policy.runningCue.minAlpha, 0.001f)
        assertEquals(0.25f, policy.runningCue.maxAlpha, 0.001f)

        // 2. Insertion (.5 timeline primitives)
        assertEquals(EnterTransition.None, policy.insertion.enter)
        assertEquals(ExitTransition.None, policy.insertion.exit)
        assertTrue(policy.insertion.sizeSpec is SnapSpec)

        // 3. Expansion (.8 auto-expand/collapse)
        assertEquals(EnterTransition.None, policy.expansion.enter)
        assertEquals(ExitTransition.None, policy.expansion.exit)
        assertTrue(policy.expansion.sizeSpec is SnapSpec)

        // 4. Staged Collapse (.8 auto-expand/collapse)
        assertEquals(EnterTransition.None, policy.stagedCollapse.enter)
        assertEquals(ExitTransition.None, policy.stagedCollapse.exit)
        assertTrue(policy.stagedCollapse.sizeSpec is SnapSpec)

        // 5. Terminal Swap (.10 streaming markdown fade, .11 reasoning typewriter)
        assertEquals(EnterTransition.None, policy.terminalSwap.enter)
        assertEquals(ExitTransition.None, policy.terminalSwap.exit)
        assertTrue(policy.terminalSwap.crossfadeSpec is SnapSpec)
        assertEquals(0L, policy.terminalSwap.typewriterStepDelayMillis)
    }

    @Test
    fun `standard motion policy contract provides expressive curves for all five roles`() {
        val policy = ChatMotionPolicy.Standard

        assertFalse(policy.isReducedMotionEnabled)

        // 1. Running Cue
        assertTrue(policy.runningCue.allowInfiniteAnimation)
        assertEquals(0.15f, policy.runningCue.minAlpha, 0.001f)
        assertEquals(0.35f, policy.runningCue.maxAlpha, 0.001f)

        // 2. Insertion
        assertNotEquals(EnterTransition.None, policy.insertion.enter)
        assertNotEquals(ExitTransition.None, policy.insertion.exit)

        // 3. Expansion
        assertNotEquals(EnterTransition.None, policy.expansion.enter)
        assertNotEquals(ExitTransition.None, policy.expansion.exit)

        // 4. Staged Collapse
        assertNotEquals(EnterTransition.None, policy.stagedCollapse.enter)
        assertNotEquals(ExitTransition.None, policy.stagedCollapse.exit)

        // 5. Terminal Swap
        assertNotEquals(EnterTransition.None, policy.terminalSwap.enter)
        assertEquals(15L, policy.terminalSwap.typewriterStepDelayMillis)
    }

    @Test
    fun `policy lookup returns cached singletons with zero per-frame allocation`() {
        assertSame(ChatMotionPolicy.Reduced, ChatMotionPolicy.of(reducedMotionEnabled = true))
        assertSame(ChatMotionPolicy.Standard, ChatMotionPolicy.of(reducedMotionEnabled = false))

        assertEquals(EnterTransition.None, ChatMotionTokens.InstantEnter)
        assertEquals(ExitTransition.None, ChatMotionTokens.InstantExit)
        assertTrue(ChatMotionTokens.InstantSizeSpec is SnapSpec)
        assertTrue(ChatMotionTokens.InstantFloatSpec is SnapSpec)
    }
}
