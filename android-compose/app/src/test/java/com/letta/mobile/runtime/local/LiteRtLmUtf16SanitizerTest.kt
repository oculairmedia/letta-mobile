package com.letta.mobile.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtLmUtf16SanitizerTest {

    @Test
    fun `removes surrogate pairs from supplementary-plane emoji`() {
        // 🗺️ (U+1F5FA U+FE0F) = surrogate pair + variation selector
        val input = "\uD83D\uDDFA\uFE0F hi"
        val sanitized = sanitizeForLiteRt(input)
        // Variation selector (U+FE0F) is BMP, survives. Surrogate pair dropped.
        assertEquals("\uFE0F hi", sanitized)
    }

    @Test
    fun `preserves BMP text unchanged`() {
        val input = "héllo \u2694\uFE0F 你好"  // ⚔️ with VS16
        val sanitized = sanitizeForLiteRt(input)
        assertEquals(input, sanitized)
    }

    @Test
    fun `preserves plain ASCII unchanged`() {
        val input = "hello world 123"
        val sanitized = sanitizeForLiteRt(input)
        assertEquals(input, sanitized)
    }

    @Test
    fun `preserves BMP symbols`() {
        // ❤️ (U+2764 U+FE0F) — both BMP code points
        val input = "\u2764\uFE0F love"
        val sanitized = sanitizeForLiteRt(input)
        assertEquals(input, sanitized)
    }

    @Test
    fun `empty string returns empty`() {
        assertEquals("", sanitizeForLiteRt(""))
    }

    @Test
    fun `string with only surrogates returns empty`() {
        // U+1F600 😀 encoded as surrogate pair
        assertEquals("", sanitizeForLiteRt("\uD83D\uDE00"))
    }

    @Test
    fun `preserves BMP characters adjacent to surrogates`() {
        // 🎉 (U+1F389 = \uD83C\uDF89) + "yay"
        val input = "prefix\uD83C\uDF89yay"
        val sanitized = sanitizeForLiteRt(input)
        assertEquals("prefixyay", sanitized)
    }

    @Test
    fun `preserves CJK characters`() {
        val input = "日本語テスト"
        val sanitized = sanitizeForLiteRt(input)
        assertEquals(input, sanitized)
    }

    @Test
    fun `preserves newlines and special BMP chars`() {
        val input = "line1\nline2\tindented\r\nwindows"
        val sanitized = sanitizeForLiteRt(input)
        assertEquals(input, sanitized)
    }
}
