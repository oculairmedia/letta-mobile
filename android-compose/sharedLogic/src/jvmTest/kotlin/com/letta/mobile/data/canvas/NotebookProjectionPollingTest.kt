package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.automerge.repo.DocumentId

/**
 * Polling syncs only what changed: once every notebook is synced, idle polls skip them all, and an
 * edit on either side (the notebook or its projected files) is still picked up on the next poll.
 */
class NotebookProjectionPollingTest {
    @Test
    fun idlePollsSkipUnchangedNotebooksButEditsStillSync() {
        val directory = Files.createTempDirectory("notebook-poll-idle-")
        NotebookLocalStore(directory, "poll-idle-peer").use { store ->
            val edited = store.create("Edited")
            val external = store.create("External")
            val results = Collections.synchronizedList(mutableListOf<Pair<DocumentId, NotebookProjectionResult>>())
            store.startPolling(POLL_MS) { id, result -> results += id to result }

            awaitResult(results, edited, NotebookProjectionResult.EXPORTED)
            awaitResult(results, external, NotebookProjectionResult.EXPORTED)
            Thread.sleep(IDLE_MS)
            val beforeIdle = results.size
            Thread.sleep(IDLE_MS)
            assertEquals(beforeIdle, results.size, "idle polls reported nothing: ${results.drop(beforeIdle)}")

            store.insertMarkdown(edited, 0, "Local edit")
            awaitResult(results, edited, NotebookProjectionResult.EXPORTED, after = beforeIdle)

            Files.writeString(noteFile(directory, "External"), "---\nschema: notebook/1\ntitle: \"External\"\n---\nFrom disk")
            awaitResult(results, external, NotebookProjectionResult.IMPORTED, after = beforeIdle)
            assertEquals("From disk", store.read(external)?.markdown)
        }
    }

    private fun awaitResult(
        results: List<Pair<DocumentId, NotebookProjectionResult>>,
        id: DocumentId,
        expected: NotebookProjectionResult,
        after: Int = 0,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (synchronized(results) { results.drop(after).any { it == id to expected } }) return
            Thread.sleep(POLL_MS)
        }
        assertTrue(false, "$expected for $id never arrived; saw ${synchronized(results) { results.toList() }}")
    }

    private fun noteFile(directory: Path, title: String): Path = Files.list(directory.resolve("projection")).use { folders ->
        folders.map { it.resolve("note.md") }.filter { Files.readString(it).contains("\"$title\"") }.findFirst().orElseThrow()
    }

    private companion object {
        const val POLL_MS = 20L
        const val IDLE_MS = 300L
        const val WAIT_SECONDS = 10L
    }
}
