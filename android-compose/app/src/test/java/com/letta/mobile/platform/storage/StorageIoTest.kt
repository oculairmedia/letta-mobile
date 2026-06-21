package com.letta.mobile.platform.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class StorageIoTest {

    @Test
    fun `readCapped with stream smaller than maxBytes`() {
        val data = "hello".encodeToByteArray()
        val inputStream = ByteArrayInputStream(data)

        val (bytes, truncated) = inputStream.readCapped(10)

        assertArrayEquals(data, bytes)
        assertFalse(truncated)
    }

    @Test
    fun `readCapped with stream exactly maxBytes`() {
        val data = "hello".encodeToByteArray()
        val inputStream = ByteArrayInputStream(data)

        val (bytes, truncated) = inputStream.readCapped(5)

        assertArrayEquals(data, bytes)
        assertFalse(truncated)
    }

    @Test
    fun `readCapped with stream larger than maxBytes`() {
        val data = "hello world".encodeToByteArray()
        val inputStream = ByteArrayInputStream(data)

        val (bytes, truncated) = inputStream.readCapped(5)

        assertArrayEquals("hello".encodeToByteArray(), bytes)
        assertTrue(truncated)
    }

    @Test
    fun `readCapped with empty stream`() {
        val data = ByteArray(0)
        val inputStream = ByteArrayInputStream(data)

        val (bytes, truncated) = inputStream.readCapped(5)

        assertArrayEquals(ByteArray(0), bytes)
        assertFalse(truncated)
    }

    @Test
    fun `readCapped with zero maxBytes and non-empty stream`() {
        val data = "hello".encodeToByteArray()
        val inputStream = ByteArrayInputStream(data)

        val (bytes, truncated) = inputStream.readCapped(0)

        assertArrayEquals(ByteArray(0), bytes)
        assertTrue(truncated)
    }

    @Test
    fun `readCapped with zero maxBytes and empty stream`() {
        val data = ByteArray(0)
        val inputStream = ByteArrayInputStream(data)

        val (bytes, truncated) = inputStream.readCapped(0)

        assertArrayEquals(ByteArray(0), bytes)
        assertFalse(truncated)
    }
}
