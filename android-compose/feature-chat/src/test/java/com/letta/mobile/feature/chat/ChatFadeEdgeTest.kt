package com.letta.mobile.feature.chat

import androidx.compose.ui.graphics.Color
import com.letta.mobile.feature.chat.screen.chatFadeShowBottom
import com.letta.mobile.feature.chat.screen.chatFadeShowTop
import com.letta.mobile.feature.chat.screen.chatFadeTargetColor
import com.letta.mobile.ui.theme.ChatBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFadeEdgeTest {

    @Test
    fun `top fade shows only when there is content scrolled off the top`() {
        assertTrue(chatFadeShowTop(canScrollForward = true))
        assertFalse(chatFadeShowTop(canScrollForward = false))
    }

    @Test
    fun `bottom fade shows only when there is content scrolled off the bottom`() {
        assertTrue(chatFadeShowBottom(canScrollBackward = true))
        assertFalse(chatFadeShowBottom(canScrollBackward = false))
    }

    @Test
    fun `fade target uses solid chat background color so it blends exactly`() {
        assertEquals(
            Color(0xFF123456),
            chatFadeTargetColor(
                chatBackground = scrollTestChatBackgroundSolid,
                fallbackContainerColor = Color(0xFF999999),
            ),
        )
    }

    @Test
    fun `fade target falls back to container color for default and gradient backgrounds`() {
        val fallback = Color(0xFF222222)
        assertEquals(
            fallback,
            chatFadeTargetColor(
                chatBackground = ChatBackground.Default,
                fallbackContainerColor = fallback,
            ),
        )
        assertEquals(
            fallback,
            chatFadeTargetColor(
                chatBackground = ChatBackground.Gradient(
                    colors = listOf(Color(0xFF000000), Color(0xFFFFFFFF)),
                    name = "Mono",
                ),
                fallbackContainerColor = fallback,
            ),
        )
    }
}
