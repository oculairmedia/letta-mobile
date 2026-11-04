package com.letta.mobile.data.controller.node.iroh

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicJsonFileTest {
    @Test
    fun requiredDirectorySyncPropagatesIoFailure() {
        val dir = Files.createTempDirectory("atomic-json")
        try {
            assertFailsWith<IOException> {
                AtomicJsonFile.write(dir.resolve("state"), "{}", requireAtomic = true) { throw IOException("sync failed") }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun unsupportedDirectorySyncIsBestEffortAndOrdinaryWritesRemainBestEffort() {
        val dir = Files.createTempDirectory("atomic-json")
        try {
            val path = dir.resolve("state")
            AtomicJsonFile.write(path, "one", requireAtomic = true) { throw UnsupportedOperationException() }
            assertEquals("one", Files.readString(path))
            AtomicJsonFile.write(path, "two") { throw IOException("sync failed") }
            assertEquals("two", Files.readString(path))
        } finally { dir.toFile().deleteRecursively() }
    }
}
