package com.letta.mobile.platform.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StorageToolModelsTest {

    @Test
    fun `StorageAccessLimits accepts valid positive inputs`() {
        val limits = StorageAccessLimits(
            maxReadBytes = 100L,
            maxWriteBytes = 200L,
            maxDirectoryEntries = 10
        )

        assertEquals(100L, limits.maxReadBytes)
        assertEquals(200L, limits.maxWriteBytes)
        assertEquals(10, limits.maxDirectoryEntries)
    }

    @Test
    fun `StorageAccessLimits uses default values when not provided`() {
        val limits = StorageAccessLimits()

        assertEquals(StorageAccessLimits.DEFAULT_MAX_READ_BYTES, limits.maxReadBytes)
        assertEquals(StorageAccessLimits.DEFAULT_MAX_WRITE_BYTES, limits.maxWriteBytes)
        assertEquals(StorageAccessLimits.DEFAULT_MAX_DIRECTORY_ENTRIES, limits.maxDirectoryEntries)
    }

    @Test
    fun `StorageAccessLimits rejects zero maxReadBytes`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StorageAccessLimits(maxReadBytes = 0L)
        }
        assertEquals("maxReadBytes must be positive", exception.message)
    }

    @Test
    fun `StorageAccessLimits rejects negative maxReadBytes`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StorageAccessLimits(maxReadBytes = -1L)
        }
        assertEquals("maxReadBytes must be positive", exception.message)
    }

    @Test
    fun `StorageAccessLimits rejects zero maxWriteBytes`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StorageAccessLimits(maxWriteBytes = 0L)
        }
        assertEquals("maxWriteBytes must be positive", exception.message)
    }

    @Test
    fun `StorageAccessLimits rejects negative maxWriteBytes`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StorageAccessLimits(maxWriteBytes = -10L)
        }
        assertEquals("maxWriteBytes must be positive", exception.message)
    }

    @Test
    fun `StorageAccessLimits rejects zero maxDirectoryEntries`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StorageAccessLimits(maxDirectoryEntries = 0)
        }
        assertEquals("maxDirectoryEntries must be positive", exception.message)
    }

    @Test
    fun `StorageAccessLimits rejects negative maxDirectoryEntries`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StorageAccessLimits(maxDirectoryEntries = -5)
        }
        assertEquals("maxDirectoryEntries must be positive", exception.message)
    }
}
