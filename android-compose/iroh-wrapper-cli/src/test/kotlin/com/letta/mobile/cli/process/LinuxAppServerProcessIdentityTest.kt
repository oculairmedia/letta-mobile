package com.letta.mobile.cli.process

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull

class LinuxAppServerProcessIdentityTest {
    @TempDir
    lateinit var root: Path

    private fun fixture(start: Long = 100): LinuxAppServerProcessIdentity {
        Files.createDirectories(root.resolve("sys/kernel/random"))
        Files.writeString(root.resolve("sys/kernel/random/boot_id"), BOOT)
        Files.writeString(root.resolve("main.pid"), "42\n")
        writeStat(start)
        return LinuxAppServerProcessIdentity("ws://127.0.0.1:4500", root.resolve("main.pid"), root)
    }

    private fun writeStat(start: Long) {
        Files.createDirectories(root.resolve("42"))
        // Fields 3..21, then field 22. The comm field may itself contain parentheses/spaces.
        Files.writeString(root.resolve("42/stat"), "42 (node (worker)) S " + List(18) { "0" }.joinToString(" ") + " $start 0\n")
    }

    @Test
    fun pidReuseChangesIdentityButRepeatedReadsAreStable() {
        val reader = fixture()
        assertEquals("$BOOT:42:100", reader.current())
        assertEquals(reader.current(), reader.current())
        val previous = reader.current()
        writeStat(200)
        assertNotEquals(previous, reader.current())
        assertEquals("$BOOT:42:200", reader.current())
    }

    @Test
    fun bootChangeChangesIdentity() {
        val reader = fixture()
        val previous = reader.current()
        Files.writeString(root.resolve("sys/kernel/random/boot_id"), "b3a51d48-1e92-4ad0-a3b2-38205ca95139")
        assertNotEquals(previous, reader.current())
    }

    @Test
    fun missingOrMalformedOrDeadProcessFailsClosed() {
        val reader = fixture()
        Files.writeString(root.resolve("42/stat"), "42 (node) Z 0")
        assertNull(reader.current())
        Files.writeString(root.resolve("42/stat"), "invalid")
        assertNull(reader.current())
        Files.delete(root.resolve("42/stat"))
        assertNull(reader.current())
        assertNull(LinuxAppServerProcessIdentity("ws://127.0.0.1:4500", root.resolve("missing"), root).current())
    }

    @Test
    fun remoteUnsupportedAndUnconfiguredHostsAreUnknown() {
        fixture()
        listOf("ws://example.org:4500", "ws://127.0.0.1.example.org:4500", "not a url", "http://127.0.0.1:4500").forEach {
            assertNull(LinuxAppServerProcessIdentity(it, root.resolve("main.pid"), root).current())
        }
        assertNull(LinuxAppServerProcessIdentity("ws://127.0.0.1:4500", null, root).current())
        assertNull(LinuxAppServerProcessIdentity("ws://127.0.0.1:4500", root.resolve("main.pid"), root.resolve("absent-proc")).current())
    }

    private companion object {
        const val BOOT = "a3a51d48-1e92-4ad0-a3b2-38205ca95139"
    }
}
