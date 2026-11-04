package com.letta.mobile.data.controller.node.iroh

import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AtomicJsonFileTest {
    @Test
    fun windowsDirectoryOpenIsSkippedBeforeAccessDenied() {
        val dir = Files.createTempDirectory("atomic-windows")
        val prior = System.getProperty("os.name")
        try {
            System.setProperty("os.name", "Windows 11")
            var syncs = 0
            val result = runCatching {
                AtomicJsonFile.write(dir.resolve("state"), "durable", requireAtomic = true) {
                    syncs++
                    throw java.nio.file.AccessDeniedException(it.toString())
                }
            }
            assertEquals(null, result.exceptionOrNull(), "Windows directory capability must skip opening the channel")
            assertEquals("durable", Files.readString(dir.resolve("state")))
            assertEquals(0, syncs, "unsupported directory channels must not be opened")
        } finally {
            System.setProperty("os.name", prior)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun injectedDirectoryCapabilitySkipsUnsupportedButPropagatesRealAccessDenied() {
        val dir = Files.createTempDirectory("atomic-capability")
        try {
            val path = dir.resolve("state")
            var syncs = 0
            val sync: (java.nio.file.Path) -> Unit = {
                syncs++
                throw java.nio.file.AccessDeniedException(it.toString())
            }
            AtomicJsonFile.write(path, "unsupported", requireAtomic = true, directorySyncSupported = false, syncDirectory = sync)
            assertEquals("unsupported", Files.readString(path))
            assertEquals(0, syncs)
            assertFailsWith<java.nio.file.AccessDeniedException> {
                AtomicJsonFile.write(path, "supported", requireAtomic = true, directorySyncSupported = true, syncDirectory = sync)
            }
            assertEquals(1, syncs, "real permission and I/O failures on supported platforms must propagate")
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun requiredDirectorySyncPropagatesIoFailure() {
        val dir = Files.createTempDirectory("atomic-json")
        try {
            assertFailsWith<IOException> {
                AtomicJsonFile.write(dir.resolve("state"), "{}", requireAtomic = true, directorySyncSupported = true) {
                    throw IOException("sync failed")
                }
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
