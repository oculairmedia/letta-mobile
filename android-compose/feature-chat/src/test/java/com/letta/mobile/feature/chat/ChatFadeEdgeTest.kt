package com.letta.mobile.feature.chat

import androidx.compose.ui.graphics.Color
import com.letta.mobile.feature.chat.screen.chatFadeShowBottom
import com.letta.mobile.feature.chat.screen.chatFadeShowTop
import com.letta.mobile.feature.chat.screen.chatFadeScrimColor
import com.letta.mobile.feature.chat.screen.chatFadeTargetColor
import com.letta.mobile.ui.theme.ChatBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `the darkening pass paints the chat surface, not the lighter container`() {
        // It lies over the ambient glow and is meant to darken it. Painting the scaffold
        // container instead washed the bottom band toward grey, because the container is
        // LIGHTER than the surface the chat is drawn on.
        val surface = Color(0xFF0E0E12)
        val container = Color(0xFF211F26)
        assertEquals(surface, chatFadeScrimColor(ChatBackground.Default, surfaceColor = surface))
        assertNotEquals(
            container,
            chatFadeScrimColor(ChatBackground.Default, surfaceColor = surface),
        )
    }

    @Test
    fun `a solid or gradient chat background darkens toward its own colour`() {
        val solid = Color(0xFF123456)
        assertEquals(
            solid,
            chatFadeScrimColor(ChatBackground.SolidColor(solid, "test"), surfaceColor = Color.Red),
        )
        // A vertical gradient ends at its last colour, which is what the bottom band sits on.
        val bottom = Color(0xFF654321)
        assertEquals(
            bottom,
            chatFadeScrimColor(
                ChatBackground.Gradient(listOf(Color(0xFF111111), bottom), "test"),
                surfaceColor = Color.Red,
            ),
        )
    }
}
