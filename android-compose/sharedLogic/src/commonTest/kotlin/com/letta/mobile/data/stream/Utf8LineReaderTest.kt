package com.letta.mobile.data.stream

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-h30cy: a multi-byte UTF-8 codepoint (e.g. em dash "—" = E2 80 94)
 * split across two reads must NOT drop the char. The reader carries the partial
 * trailing bytes forward.
 */
class Utf8LineReaderTest {

    @Test
    fun `completeUtf8Length keeps whole ascii`() {
        assertEquals(3, Utf8LineReader.completeUtf8Length("abc".encodeToByteArray()))
    }

    @Test
    fun `completeUtf8Length excludes a truncated multi-byte tail`() {
        val emDash = byteArrayOf(0xE2.toByte(), 0x80.toByte(), 0x94.toByte()) // —
        // "a" + first 2 of 3 em-dash bytes → only the "a" is complete (index 1).
        val truncated = byteArrayOf('a'.code.toByte()) + emDash.copyOfRange(0, 2)
        assertEquals(1, Utf8LineReader.completeUtf8Length(truncated))
        // Full em dash present → all bytes complete.
        val whole = byteArrayOf('a'.code.toByte()) + emDash
        assertEquals(whole.size, Utf8LineReader.completeUtf8Length(whole))
    }

    @Test
    fun `em dash split across reads survives intact`() = runTest {
        val text = "a—b\n" // em dash between a and b
        val bytes = text.encodeToByteArray()
        // Find the em-dash byte offset and split the stream right in the middle
        // of its 3-byte sequence (after the first em-dash byte).
        val emStart = "a".encodeToByteArray().size
        val splitAt = emStart + 1 // mid-codepoint
        // Force a tiny reader chunk so readAvailable returns few bytes per call,
        // guaranteeing the em dash's 3 bytes span multiple reads (the boundary
        // this test guards). A pre-filled ByteReadChannel delivers all bytes.
        val channel = io.ktor.utils.io.ByteReadChannel(bytes)
        val reader = Utf8LineReader(channel, chunkSize = splitAt)
        assertEquals("a—b", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `plain lines still split on newline`() = runTest {
        val channel = io.ktor.utils.io.ByteReadChannel("hello\nworld\n".encodeToByteArray())
        val reader = Utf8LineReader(channel, chunkSize = 3)
        assertEquals("hello", reader.readLine())
        assertEquals("world", reader.readLine())
        assertNull(reader.readLine())
    }

}
