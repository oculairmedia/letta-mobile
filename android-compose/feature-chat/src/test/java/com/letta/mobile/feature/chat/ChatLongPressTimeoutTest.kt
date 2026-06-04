package com.letta.mobile.feature.chat

import com.letta.mobile.feature.chat.screen.CHAT_LONG_PRESS_TIMEOUT_MULTIPLIER
import com.letta.mobile.feature.chat.screen.chatLongPressTimeoutMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * letta-mobile-y1ogc: the chat-timeline long-press hold threshold must be
 * roughly double the platform default so incidental touches during scroll no
 * longer trigger the long-press-to-copy gesture.
 */
class ChatLongPressTimeoutTest {

    @Test
    fun `multiplier doubles the threshold`() {
        assertEquals(2L, CHAT_LONG_PRESS_TIMEOUT_MULTIPLIER)
    }

    @Test
    fun `chat long-press timeout doubles the platform default of 400ms`() {
        assertEquals(800L, chatLongPressTimeoutMillis(400L))
    }

    @Test
    fun `chat long-press timeout doubles the platform default of 500ms`() {
        assertEquals(1000L, chatLongPressTimeoutMillis(500L))
    }

    @Test
    fun `chat long-press timeout is always at least double the platform value`() {
        for (platform in longArrayOf(300L, 400L, 450L, 500L, 600L)) {
            assertTrue(chatLongPressTimeoutMillis(platform) >= platform * 2)
        }
    }
}
