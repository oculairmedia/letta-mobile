package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.automerge.repo.DocumentId

/**
 * Projection polling follows an activity signal (on Android, whether the app is in the
 * foreground): no polls while inactive, a catch-up as soon as it is active again, and switching
 * never waits for a poll that holds the store.
 */
class NotebookProjectionLifecycleTest {
    private val directory = Files.createTempDirectory("notebook-poll-lifecycle-")

    @Test
    fun inactiveStoreDoesNotPollAndCatchesUpWhenActiveAgain() = runBlocking {
        NotebookLocalStore(directory, "lifecycle-peer").use { store ->
            val id = store.create("Paused")
            val active = MutableStateFlow(true)
            val following = launch(Dispatchers.Default) { store.pollProjectionsWhile(active, POLL_MS) }
            try {
                awaitTrue("the first poll projects the notebook") { projectedNote() != null }

                active.value = false
                Thread.sleep(SETTLE_MS)
                Files.writeString(requireNotNull(projectedNote()), projectedNote("Paused", "Edited while paused"))
                Thread.sleep(PAUSED_MS)
                assertEquals("", markdownOf(store, id), "an inactive store imported an edit, so it was still polling")

                active.value = true
                awaitTrue("the edit made while inactive is imported once active") { markdownOf(store, id) == "Edited while paused" }
            } finally {
                following.cancelAndJoin()
            }
        }
    }

    @Test
    fun stoppingDoesNotWaitForAPollHoldingTheStore() {
        NotebookLocalStore(directory, "lifecycle-lock-peer").use { store ->
            store.startPolling(POLL_MS)
            val held = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder = thread { synchronized(store) { held.countDown(); release.await(WAIT_SECONDS, TimeUnit.SECONDS) } }
            assertTrue(held.await(WAIT_SECONDS, TimeUnit.SECONDS))

            val stopped = CountDownLatch(1)
            thread { store.stopPolling(); store.startPolling(POLL_MS); stopped.countDown() }
            val returned = stopped.await(SWITCH_LIMIT_MS, TimeUnit.MILLISECONDS)
            release.countDown()
            holder.join()
            assertTrue(returned, "stopping and restarting polling waited for a poll holding the store")
        }
    }

    private fun markdownOf(store: NotebookLocalStore, id: DocumentId): String? = store.read(id)?.markdown

    private fun projectedNote(): Path? = directory.resolve("projection").takeIf(Files::isDirectory)?.let { root ->
        Files.list(root).use { folders -> folders.map { it.resolve("note.md") }.filter(Files::exists).findFirst().orElse(null) }
    }

    private fun projectedNote(title: String, body: String) = "---\nschema: notebook/1\ntitle: \"$title\"\n---\n$body"

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        assertTrue(false, "never happened: $what")
    }

    private companion object {
        const val POLL_MS = 20L

        /** Long enough for a poll already running when polling stopped to finish. */
        const val SETTLE_MS = 200L

        /** Many poll intervals: an active store imports the edit well within this. */
        const val PAUSED_MS = 500L
        const val SWITCH_LIMIT_MS = 1_000L
        const val WAIT_SECONDS = 10L
    }
}
