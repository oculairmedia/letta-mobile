package com.letta.mobile.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageContentFactoryTest {

    @Test
    fun `tool call entrance animation is limited to active streaming`() {
        assertTrue(shouldAnimateToolCallEntrance(isStreaming = true))
        assertFalse(shouldAnimateToolCallEntrance(isStreaming = false))
    }
}
