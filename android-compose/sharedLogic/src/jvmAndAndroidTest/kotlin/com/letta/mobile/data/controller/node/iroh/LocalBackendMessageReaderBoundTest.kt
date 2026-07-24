package com.letta.mobile.data.controller.node.iroh

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bounded-transcript guard for [LocalBackendMessageReader.readLocalMessages]
 * (audit P3.3 / gn7kr.22): an in-budget transcript is read to completion (parity
 * with the admin-shim), a pathological one is truncated to a bounded prefix
 * instead of OOMing.
 */
class LocalBackendMessageReaderBoundTest {

    private fun support(): LocalBackendStoreSupport {
        val baseDir = Files.createTempDirectory("lbmr-bound").toFile()
        baseDir.deleteOnExit()
        return LocalBackendStoreSupport(baseDir = baseDir, lmstudioBaseUrl = "http://127.0.0.1:0")
    }

    private fun writeMessages(count: Int): File {
        val file = Files.createTempFile("messages", ".jsonl").toFile()
        file.deleteOnExit()
        file.bufferedWriter().use { w ->
            repeat(count) { i ->
                w.write("""{"id":"m$i","role":"user","content":[{"type":"text","text":"hello $i"}]}""")
                w.write("\n")
            }
        }
        return file
    }

    @Test
    fun readsEveryMessageWhenUnderTheCap() {
        val support = support()
        val reader = LocalBackendMessageReader(support)
        val file = writeMessages(50)

        val messages = reader.readLocalMessages(file)

        assertEquals(50, messages.size)
        assertEquals("m0", messages.first()["id"]?.stringOrNull())
        assertEquals("m49", messages.last()["id"]?.stringOrNull())
    }

    @Test
    fun stopsAtTheMessageCountCap() {
        val support = support()
        val reader = LocalBackendMessageReader(
            support = support,
            maxTranscriptMessages = 10,
        )
        val file = writeMessages(50)

        val messages = reader.readLocalMessages(file)

        assertEquals(10, messages.size)
        assertEquals("m0", messages.first()["id"]?.stringOrNull())
        assertEquals("m9", messages.last()["id"]?.stringOrNull())
    }

    @Test
    fun stopsAtTheByteCap() {
        val support = support()
        val reader = LocalBackendMessageReader(
            support = support,
            maxTranscriptBytes = 200L,
        )
        val file = writeMessages(50)

        val messages = reader.readLocalMessages(file)

        assertTrue(messages.size in 1 until 50, "expected a truncated prefix, got ${messages.size}")
    }
}
