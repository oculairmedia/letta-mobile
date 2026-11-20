package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The journal directory's own bookkeeping: what it reports as freed must really be gone. */
class FeedLogFilesTest {
    private val root = createTempDirectory("feed-files")
    private val key = JournalKey.of(AgentId("agent-a"), ConversationId("conv-1"))

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun aFileThatCannotBeDeletedIsNeitherReportedFreedNorSubtractedFromTheDiskTotal() {
        val stuck = root.resolve("stuck.jsonl").also { it.writeText("x".repeat(100)) }
        val attempted = mutableListOf<Path>()
        val ops = FileOps(delete = { file ->
            attempted.add(file)
            throw IOException("cannot delete")
        })
        val files = FeedLogFiles(root, ceilingBytes = 1, ops = ops)
        val before = files.diskBytes
        assertTrue(before.value >= 100, "the journal file counts towards the total: ${before.value}")

        val deleted = files.enforceCeiling(emptySet(), { 0L }) { _, _ -> }

        assertEquals(listOf(stuck), attempted, "eviction really ran and tried to delete the file")
        assertTrue(Files.exists(stuck), "the file is still there")
        assertEquals(emptyList(), deleted, "nothing was freed, so nothing is reported freed")
        assertEquals(before, files.diskBytes, "and the disk total is unchanged")
    }

    @Test
    fun aFailedAppendThatLandedSomeBytesKeepsTheDiskTotalEqualToTheRealFileSize() {
        val partial = FileOps(append = { file, bytes ->
            Files.write(file, bytes.copyOf(bytes.size / 2), StandardOpenOption.APPEND)
            throw IOException("disk full after half")
        })
        val files = FeedLogFiles(root, ceilingBytes = Long.MAX_VALUE, ops = partial)
        val log = FeedLog(key, files.fileFor(key))
        files.write(log, "{\"first\":true}")
        files.write(log, "{\"second\":\"will fail halfway\"}")

        val actual = Files.size(checkNotNull(log.file))

        assertTrue(log.degraded, "the log went memory-only")
        assertEquals(actual, files.diskBytes.value, "the disk total matches the bytes that really landed")
        assertEquals(actual, log.fileBytes.value)
    }

    @Test
    fun theDirectoryOfANewJournalIsSyncedAfterItIsCreatedOrReplaced() {
        val synced = mutableListOf<Path>()
        val files = FeedLogFiles(root, ceilingBytes = Long.MAX_VALUE, ops = FileOps(syncParentOf = { synced.add(it) }))
        val log = FeedLog(key, files.fileFor(key))

        files.write(log, "{\"first\":true}")
        files.rewrite(log)

        assertEquals(listOf(log.file, log.file), synced, "once for the created file, once for the replacement")
    }

    @Test
    fun aFilesystemWithNoWayToMakeTheFileOwnerOnlyWritesNothingAndDegradesTheLog() {
        val refusing = FileOps(writeOwnerOnly = { _, _ -> throw IllegalArgumentException("no ACL view") })
        val files = FeedLogFiles(root, ceilingBytes = Long.MAX_VALUE, ops = refusing)
        val log = FeedLog(key, files.fileFor(key))

        files.write(log, "{\"first\":true}")

        assertTrue(log.degraded, "memory-only, and no exception escaped")
        assertTrue(!Files.exists(checkNotNull(log.file)), "conversation text is never written to a file that is not owner-only")
    }
}
