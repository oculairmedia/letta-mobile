package com.letta.mobile.data.secrets.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretBufferTest {
    private fun SecretBuffer.text(): String = revealChars { view -> CharArray(view.length) { view[it] }.concatToString() }

    private fun SecretInput.text(): String = revealSecretBytes { it.decodeToString() }

    @Test
    fun editsInsertReplaceAndDeleteLikeAField() {
        val buffer = SecretBuffer()
        assertTrue(buffer.replace(at(0), "hello"))
        assertTrue(buffer.replace(at(5), " world"))
        assertTrue(buffer.replace(0 until 5, "HELLO"))
        assertTrue(buffer.replace(5 until 11, ""))
        assertEquals("HELLO", buffer.text())
        assertEquals(5, buffer.length)
    }

    @Test
    fun outOfBoundsAndOversizedEditsChangeNothing() {
        val buffer = SecretBuffer()
        buffer.replace(at(0), "abc")
        assertFalse(buffer.replace(2 until 5, "x"))
        assertFalse(buffer.replace(at(4), "x"))
        val tooLong = CharArray(SecretBuffer.MAX_CHARS) { 'a' }.concatToString()
        assertFalse(buffer.replace(at(0), tooLong))
        assertEquals("abc", buffer.text())
    }

    @Test
    fun takingTheInputEncodesUtf8AndEmptiesTheBuffer() {
        // A lone surrogate becomes U+FFFD on every platform (the JVM's own encoder would write '?').
        val rows = listOf("plain", "caf\u00e9", "\u20ac10", "key-\ud83d\udd11").map { it to it } + ("lone-\ud800-x" to "lone-\ufffd-x")
        rows.forEach { (text, expected) ->
            val buffer = SecretBuffer()
            buffer.replace(at(0), text)
            val input = buffer.takeSecretInput()
            assertEquals(expected.encodeToByteArray().toList(), input.revealSecretBytes { it.toList() }, expected)
            assertTrue(buffer.isEmpty)
            assertEquals("", buffer.text())
        }
    }

    @Test
    fun growingKeepsTheContent() {
        val buffer = SecretBuffer()
        val long = CharArray(1000) { 'a' + it % 26 }.concatToString()
        buffer.replace(at(0), long)
        assertEquals(long, buffer.takeSecretInput().text())
    }

    @Test
    fun nothingPrintsTheContent() {
        val buffer = SecretBuffer()
        buffer.replace(at(0), "SENTINEL-buffer")
        assertEquals(SecretBuffer.REDACTED, buffer.toString())
        assertEquals(SecretBuffer.REDACTED, buffer.revealChars { it.toString() })
        assertFailsWith<UnsupportedOperationException> { buffer.revealChars { it.subSequence(0, 1) } }
        val input = buffer.takeSecretInput()
        assertEquals(SecretInput.REDACTED, input.toString())
        input.wipe()
        assertTrue(input.isWiped)
        assertFailsWith<IllegalStateException> { input.text() }
    }
}

/** The empty range at [position], where an insertion goes. */
internal fun at(position: Int): IntRange = position until position
