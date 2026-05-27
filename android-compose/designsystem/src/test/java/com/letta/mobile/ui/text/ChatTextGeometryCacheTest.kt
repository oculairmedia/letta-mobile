package com.letta.mobile.ui.text

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class ChatTextGeometryCacheTest {

    @Test
    fun `cache reuses geometry for identical prepared text key`() {
        val cache = ChatTextGeometryCache(maxEntries = 4)
        val key = geometryKey("hello world", widthPx = 240)
        var producerCalls = 0

        val first = cache.getOrPut(key) {
            producerCalls++
            geometry(lineCount = 1)
        }
        val second = cache.getOrPut(key) {
            producerCalls++
            geometry(lineCount = 99)
        }

        assertSame(first, second)
        assertEquals(1, producerCalls)
        assertEquals(1, second.lineCount)
    }

    @Test
    fun `cache evicts least recently used geometry`() {
        val cache = ChatTextGeometryCache(maxEntries = 2)
        val first = geometryKey("first", widthPx = 160)
        val second = geometryKey("second", widthPx = 160)
        val third = geometryKey("third", widthPx = 160)

        cache.getOrPut(first) { geometry(lineCount = 1) }
        cache.getOrPut(second) { geometry(lineCount = 2) }
        cache.get(first)
        cache.getOrPut(third) { geometry(lineCount = 3) }

        assertEquals(2, cache.size())
        assertEquals(1, cache.get(first)?.lineCount)
        assertEquals(null, cache.get(second))
        assertEquals(3, cache.get(third)?.lineCount)
    }

    @Test
    fun `content key includes enough text identity to guard hash and length reuse`() {
        val base = "a".repeat(120)
        val differentSuffix = "a".repeat(119) + "b"

        assertNotEquals(base.chatTextContentKey(), differentSuffix.chatTextContentKey())
    }

    @Test
    fun `geometry key changes for width density font scale direction style and mode`() {
        val base = geometryKey("same text", widthPx = 240)

        assertNotEquals(base, base.copy(widthPx = 320))
        assertNotEquals(base, base.copy(density = 3f))
        assertNotEquals(base, base.copy(fontScale = 1.2f))
        assertNotEquals(base, base.copy(layoutDirection = LayoutDirection.Rtl))
        assertNotEquals(base, base.copy(styleFingerprint = 42))
        assertNotEquals(base, base.copy(mode = ChatTextLayoutMode.Code))
    }

    @Test
    fun `prepared content key covers representative chat text corpus`() {
        val samples = listOf(
            "Plain English assistant prose with punctuation and wrapping.",
            "中文段落需要按字符换行，同时保持缓存键稳定。",
            "مرحبا، هذا نص عربي لاختبار اتجاه الكتابة.",
            "Emoji mix: hello 👋🏽 world 🌍 with family 👨‍👩‍👧‍👦.",
            "https://example.com/a/very/long/path?query=chat-text-geometry-cache#section",
            "```kotlin\nval answer = listOf(1, 2, 3).sum()\n```",
            (1..40).joinToString(separator = "\n") { index -> "log line $index: ${"x".repeat(index % 17 + 3)}" },
        )

        val keys = samples.map { it.chatTextContentKey() }

        assertEquals(samples.size, keys.toSet().size)
        samples.zip(keys).forEach { (sample, key) ->
            assertEquals(sample.length, key.length)
            assertEquals(sample.hashCode(), key.hash)
            assertEquals(sample.take(96), key.prefix)
            assertEquals(sample.takeLast(96), key.suffix)
        }
    }

    @Test
    fun `geometry keys distinguish corpus layout modes`() {
        val prose = geometryKey("hello world", widthPx = 240, mode = ChatTextLayoutMode.Plain)
        val markdown = geometryKey("**hello** world", widthPx = 240, mode = ChatTextLayoutMode.MarkdownParagraph)
        val code = geometryKey("hello_world = true", widthPx = 240, mode = ChatTextLayoutMode.Code)
        val preWrap = geometryKey("line 1\nline 2", widthPx = 240, mode = ChatTextLayoutMode.PreWrap)

        assertNotEquals(prose, markdown)
        assertNotEquals(prose, code)
        assertNotEquals(prose, preWrap)
        assertNotEquals(code, preWrap)
    }

    private fun geometryKey(
        text: String,
        widthPx: Int,
        mode: ChatTextLayoutMode = ChatTextLayoutMode.Plain,
    ): ChatTextGeometryKey =
        ChatTextGeometryKey(
            content = text.chatTextContentKey(),
            widthPx = widthPx,
            density = 2f,
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            styleFingerprint = 7,
            mode = mode,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
        )

    private fun geometry(lineCount: Int): ChatTextGeometry =
        ChatTextGeometry(
            lineCount = lineCount,
            heightPx = lineCount * 20,
            maxLineWidthPx = 100,
            visibleLineEndOffsets = IntArray(lineCount) { it + 1 },
        )
}
