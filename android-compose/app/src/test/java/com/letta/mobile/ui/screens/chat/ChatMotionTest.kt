package com.letta.mobile.ui.screens.chat

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMotionTest {

    @Test
    fun `chat motion timings keep streaming faster than user initiated expansion`() {
        assertTrue(ChatMotion.StreamingSizeMillis < ChatMotion.ContentSizeMillis)
        assertTrue(ChatMotion.FastFadeOutMillis < ChatMotion.ExitMillis)
        assertTrue(ChatMotion.ExitMillis < ChatMotion.EnterMillis)
        assertTrue(ChatMotion.ChipMillis <= ChatMotion.ContentSizeMillis)
    }
}
