package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.automerge.repo.DocumentId

/**
 * An idle poll asks every notebook whether it changed, once a second. Answering must cost file
 * stats only: the folder name (a SHA-256 of the notebook id) is worked out once, not on every
 * poll, while a changed size or timestamp is still noticed.
 */
class NotebookProjectionIdleCostTest {
    private val root = Files.createTempDirectory("notebook-idle-cost-")
    private val namings = AtomicInteger()
    private val projection = NotebookFilesystemProjection(root) { id -> namings.incrementAndGet(); "folder-$id" }
    private val id = DocumentId.fromBytes(ByteArray(ID_BYTES) { it.toByte() })

    /** The folder is named after the id's hex form: bytes 00 to 0f here. */
    private val note = root.resolve("folder-" + (0 until ID_BYTES).joinToString("") { "%02x".format(it) })
        .also(Files::createDirectories).resolve("note.md").also { Files.writeString(it, "body") }

    @Test
    fun idlePollsNameTheFolderOnce() {
        projection.markSynced(id, HEADS)
        repeat(IDLE_POLLS) { assertTrue(projection.isUnchangedSinceSync(id, HEADS), "poll $it saw a change") }

        assertEquals(1, namings.get(), "the projection folder was named once, not on every poll")
    }

    @Test
    fun aChangedSizeOrTimestampIsStillNoticed() {
        projection.markSynced(id, HEADS)

        Files.writeString(note, "longer body")
        assertFalse(projection.isUnchangedSinceSync(id, HEADS), "a size change is a change")

        projection.markSynced(id, HEADS)
        Files.setLastModifiedTime(note, FileTime.fromMillis(Files.getLastModifiedTime(note).toMillis() + SHIFT_MS))
        assertFalse(projection.isUnchangedSinceSync(id, HEADS), "a timestamp change is a change")

        projection.markSynced(id, HEADS)
        assertFalse(projection.isUnchangedSinceSync(id, "$HEADS-next"), "a new notebook version is a change")
    }

    private companion object {
        const val ID_BYTES = 16
        const val HEADS = "abc"
        const val IDLE_POLLS = 20
        const val SHIFT_MS = 5_000L
    }
}
