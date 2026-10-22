package com.letta.mobile.util

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedTextTest {
    @Test
    fun aStreamUnderTheCapIsReadWhole() {
        val read = ByteArrayInputStream("héllo".toByteArray()).readUtf8AtMost(64)

        assertEquals("héllo", read.text)
        assertFalse(read.truncated)
    }

    @Test
    fun aStreamPastTheCapKeepsItsHeadAndStopsReadingSoonAfter() {
        val stream = ByteArrayInputStream(ByteArray(100_000) { 'x'.code.toByte() })

        val read = stream.readUtf8AtMost(10)

        assertEquals("x".repeat(10), read.text)
        assertTrue(read.truncated)
        assertTrue(stream.available() > 50_000, "left ${stream.available()} bytes unread")
    }

    @Test
    fun aStreamExactlyAtTheCapIsNotTruncated() {
        val read = ByteArrayInputStream("abcd".toByteArray()).readUtf8AtMost(4)

        assertEquals("abcd", read.text)
        assertFalse(read.truncated)
    }
}
