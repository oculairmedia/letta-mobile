package com.letta.mobile.desktop

import com.letta.mobile.desktop.data.DesktopChatFontScaleStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopChatFontScaleTest {

    @Test
    fun `scroll up scales in and scroll down scales out`() {
        assertTrue(nextChatFontScale(DEFAULT_CHAT_FONT_SCALE, -1f) > DEFAULT_CHAT_FONT_SCALE)
        assertTrue(nextChatFontScale(DEFAULT_CHAT_FONT_SCALE, 1f) < DEFAULT_CHAT_FONT_SCALE)
    }

    @Test
    fun `zero delta is a no-op`() {
        assertEquals(1.23f, nextChatFontScale(1.23f, 0f))
    }

    @Test
    fun `scale is clamped at both ends`() {
        var out = DEFAULT_CHAT_FONT_SCALE
        repeat(50) { out = nextChatFontScale(out, 1f) }
        assertEquals(MIN_CHAT_FONT_SCALE, out)

        var into = DEFAULT_CHAT_FONT_SCALE
        repeat(50) { into = nextChatFontScale(into, -1f) }
        assertEquals(MAX_CHAT_FONT_SCALE, into)
    }

    /** In and back out must land exactly on 1.0, not 0.9999998. */
    @Test
    fun `round trip returns to the neutral scale`() {
        val inOnce = nextChatFontScale(DEFAULT_CHAT_FONT_SCALE, -1f)
        assertEquals(DEFAULT_CHAT_FONT_SCALE, nextChatFontScale(inOnce, 1f))
    }

    @Test
    fun `every reachable step stays within bounds`() {
        var scale = DEFAULT_CHAT_FONT_SCALE
        repeat(100) {
            scale = nextChatFontScale(scale, if (it % 3 == 0) 1f else -1f)
            assertTrue(scale in MIN_CHAT_FONT_SCALE..MAX_CHAT_FONT_SCALE, "out of bounds: $scale")
        }
    }

    /** A hand-edited or out-of-range persisted value must not escape the bounds. */
    @Test
    fun `restored values are clamped and snapped`() {
        assertEquals(MAX_CHAT_FONT_SCALE, snapChatFontScale(99f))
        assertEquals(MIN_CHAT_FONT_SCALE, snapChatFontScale(-4f))
        assertEquals(1.23f, snapChatFontScale(1.2345f))
    }

    @Test
    fun `store round-trips a scale across instances`() {
        val dir = Files.createTempDirectory("chat-font-scale-test")
        val path = dir.resolve("chat-font-scale.properties")
        try {
            assertNull(DesktopChatFontScaleStore(path).load(), "absent file must read as no preference")

            DesktopChatFontScaleStore(path).save(1.4f)
            assertEquals(1.4f, DesktopChatFontScaleStore(path).load())

            DesktopChatFontScaleStore(path).save(0.9f)
            assertEquals(0.9f, DesktopChatFontScaleStore(path).load())
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `a corrupt store reads as no preference rather than throwing`() {
        val dir = Files.createTempDirectory("chat-font-scale-corrupt")
        val path = dir.resolve("chat-font-scale.properties")
        try {
            Files.writeString(path, "chat.fontScale=not-a-number\n")
            assertNull(DesktopChatFontScaleStore(path).load())
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }
}
