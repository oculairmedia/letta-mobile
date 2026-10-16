package com.letta.mobile.runtime.local

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** letta-mobile-by4vm.121.11: the runtime lifecycle file survives logcat rotation, within its cap. */
class RuntimeLifecycleLogTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "runtime-lifecycle-${System.nanoTime()}")
    private val file = File(dir, "runtime-lifecycle.log")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun linesAreKeptInOrderAndTheDirectoryIsCreated() {
        val log = RotatingLineFile(file, maxBytes = 1_024)

        log.append("launch requested")
        log.append("listen line seen")

        assertEquals("launch requested\nlisten line seen\n", log.read())
    }

    @Test
    fun rotationKeepsAtMostTwoFilesAndTheNewestLines() {
        val log = RotatingLineFile(file, maxBytes = 100)

        (1..50).forEach { log.append("line %03d of the node lifecycle".format(it)) }

        val previous = File(dir, "runtime-lifecycle.log.1")
        assertTrue(file.length() <= 100)
        assertTrue(previous.length() <= 100)
        assertEquals(setOf("runtime-lifecycle.log", "runtime-lifecycle.log.1"), dir.list().orEmpty().toSet())
        val kept = log.read().lines().filter(String::isNotEmpty)
        assertEquals("line 050 of the node lifecycle", kept.last())
        assertFalse("the oldest lines rotated out", kept.contains("line 001 of the node lifecycle"))
        assertEquals(kept.sorted(), kept)
    }

    @Test
    fun anOverlongLineIsTruncatedNotDropped() {
        val log = RotatingLineFile(file, maxBytes = 10_000)

        log.append("x".repeat(5_000))

        assertEquals(2_000, log.read().trimEnd().length)
    }

    @Test
    fun anUnwritableLogNeverThrows() {
        dir.mkdirs()
        File(dir, "blocker").writeText("a file, not a directory")
        val log = RotatingLineFile(File(File(dir, "blocker"), "runtime-lifecycle.log"))

        log.append("node exited (exit=1)")

        assertEquals("", log.read())
    }

    @Test
    fun lifecycleLogStampsEachLine() {
        val log = RuntimeLifecycleLog(RotatingLineFile(file))

        log.line("[lifecycle] readyz ok 2100 ms after start")

        val line = log.read().trimEnd()
        assertTrue(line, line.endsWith(" [lifecycle] readyz ok 2100 ms after start"))
        assertTrue(line, Regex("""^\d{4}-\d{2}-\d{2}T\S+ """).containsMatchIn(line))
    }
}
