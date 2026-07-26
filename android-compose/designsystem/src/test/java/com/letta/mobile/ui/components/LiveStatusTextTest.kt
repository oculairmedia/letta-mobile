package com.letta.mobile.ui.components

import com.letta.mobile.ui.motion.ChatMotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class LiveStatusTextTest {

    @Test
    fun `standard policy allows infinite shimmer animation`() {
        val policy = ChatMotionPolicy.Standard
        assertTrue(policy.runningCue.allowInfiniteAnimation)
    }

    @Test
    fun `reduced motion policy disables infinite shimmer animation`() {
        val policy = ChatMotionPolicy.Reduced
        assertFalse(policy.runningCue.allowInfiniteAnimation)
        assertEquals(0.25f, policy.runningCue.staticAlpha, 0.001f)
    }

    @Test
    fun `policy selection maps reduced motion flag correctly`() {
        assertTrue(ChatMotionPolicy.of(reducedMotionEnabled = true).isReducedMotionEnabled)
        assertFalse(ChatMotionPolicy.of(reducedMotionEnabled = false).isReducedMotionEnabled)
    }
}
