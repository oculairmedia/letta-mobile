package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.automerge.AmValue
import org.automerge.Document
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.automerge.repo.DocumentId

class NotebookMarkdownEditTest {
    @Test
    fun insertsIntoEmptyTextAndAtBothEndsAndMiddle() = withNote { store, id ->
        val bodies = listOf("", "middle", "start middle", "start middle end", "start NEW middle end")
        bodies.zipWithNext().forEach { (expected, replacement) ->
            assertTrue(store.replaceMarkdown(id, expected, replacement))
            assertEquals(replacement, store.read(id)?.markdown)
        }
    }

    @Test
    fun deletesFromMiddleAndBothEndsAndClearsText() = withNote { store, id ->
        val bodies = listOf("start MIDDLE end", "start end", "end", "en", "")
        store.insertMarkdown(id, 0, bodies.first())
        bodies.zipWithNext().forEach { (expected, replacement) ->
            assertTrue(store.replaceMarkdown(id, expected, replacement))
            assertEquals(replacement, store.read(id)?.markdown)
        }
    }

    @Test
    fun replacesWithLongerShorterAndEntirelyDifferentText() = withNote { store, id ->
        val bodies = listOf("prefix old suffix", "prefix much longer suffix", "prefix x suffix", "aaaa", "aa", "xyz")
        store.insertMarkdown(id, 0, bodies.first())
        bodies.zipWithNext().forEach { (expected, replacement) ->
            assertTrue(store.replaceMarkdown(id, expected, replacement))
            assertEquals(replacement, store.read(id)?.markdown)
        }
    }

    @Test
    fun equalReplacementSucceedsWithoutAddingHistory() = withNote { store, id ->
        listOf("", "😀 unchanged e\u0301").forEach { body ->
            store.insertMarkdown(id, 0, body)
            val before = store.inspect(id) { it.heads }
            assertTrue(store.replaceMarkdown(id, body, body))
            assertContentEquals(before, store.inspect(id) { it.heads })
            assertEquals(body, store.read(id)?.markdown)
        }
    }

    @Test
    fun staleExpectedRejectsTheEditWithoutMutatingAnything() = withNote { store, id ->
        store.insertMarkdown(id, 0, "original")
        assertTrue(store.replaceMarkdown(id, "original", "newer"))
        val before = store.inspect(id) { it.save() }
        // Even a stale no-op or a replacement equal to the current text must be rejected.
        listOf("overwrite", "original", "newer").forEach { replacement ->
            assertFalse(store.replaceMarkdown(id, "original", replacement))
            assertEquals("newer", store.read(id)?.markdown)
            assertContentEquals(before, store.inspect(id) { it.save() })
        }
    }

    @Test
    fun unicodeEditsKeepWholeCodePointsAndExactCombiningSequences() = withNote { store, id ->
        val cases = listOf(
            "😀 alpha 🧭" to "😀 beta 🧭",
            "A😀B" to "A😃B", // Shared high surrogate must not become a partial prefix.
            "A😀B" to "A🨀B", // Shared low surrogate must not become a partial suffix.
            "😀x" to "😀yx",
            "x😀" to "xy😀",
            "a😀🧭b" to "ab",
            "😀" to "",
            "e\u0301" to "e\u0300",
            "e\u0301" to "e",
            "e\u0301" to "é", // No normalization of the editor's text.
            "👩‍💻 writes e\u0301" to "👩‍🔬 writes e\u0301",
            "\r\n😀 e\u0301\n" to "\r\n😀 changed e\u0301\n",
        )
        cases.forEach { (expected, replacement) ->
            val current = assertNotNull(store.read(id)).markdown
            assertTrue(store.replaceMarkdown(id, current, expected))
            assertTrue(store.replaceMarkdown(id, expected, replacement), "$expected -> $replacement")
            assertEquals(replacement, store.read(id)?.markdown)
        }
    }

    @Test
    fun automergeJavaIndexesTextByCodePointRatherThanUtf16OrGrapheme() = withNote { store, id ->
        store.insertMarkdown(id, 0, "A😀e\u0301Z")
        store.inspect(id) { document ->
            val textId = (document.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id
            assertEquals(5L, document.length(textId))
            document.startTransaction().use { tx ->
                tx.spliceText(textId, 2, 1, "X")
                tx.commit()
            }
        }
        assertEquals("A😀X\u0301Z", store.read(id)?.markdown)
    }

    @Test
    fun retainsTheTextObjectAndEveryCommonPrefixAndSuffixCharacter() = withNote { store, id ->
        val prefix = "😀prefix "
        val suffix = " suffix🧭"
        val expected = "${prefix}old$suffix"
        val replacement = "${prefix}much longer$suffix"
        store.insertMarkdown(id, 0, expected)
        val textId = store.inspect(id) { (it.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id }
        val positions = (0 until prefix.codePointCount(0, prefix.length)) +
            (expected.codePointCount(0, expected.length) - suffix.codePointCount(0, suffix.length) until
                expected.codePointCount(0, expected.length))
        val cursors = store.inspect(id) { document ->
            positions.associateWith { document.makeCursor(textId, it.toLong()).toString() }
        }

        assertTrue(store.replaceMarkdown(id, expected, replacement))

        store.inspect(id) { document ->
            assertEquals(textId, (document.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id)
            cursors.forEach { (oldPosition, cursor) ->
                val shift = if (oldPosition < prefix.codePointCount(0, prefix.length)) 0 else "much longer".length - "old".length
                assertEquals(cursor, document.makeCursor(textId, (oldPosition + shift).toLong()).toString())
            }
        }
        assertEquals(replacement, store.read(id)?.markdown)
    }

    @Test
    fun editAndOpaqueFieldsSurviveDurableRestartWithoutCanvasMapping() {
        val directory = Files.createTempDirectory("notebook-markdown-restart-")
        val id: DocumentId
        val textId: ObjectId
        val vendorId: ObjectId
        val board: String
        val item = NotebookItem("future-item", "vendor.future", version = 7, payloadJson = """{"unknown":[1,{"keep":true}]}""")
        val opaqueMetadata = """{"future":"vendor metadata stays opaque"}"""
        NotebookLocalStore(directory, "markdown-restart-peer").use { store ->
            id = store.create("Keep title")
            store.insertMarkdown(id, 0, "😀 old e\u0301")
            store.putItem(id, item)
            store.setBoard(id, """{"schema":"notebook-board/1","elements":[{"id":"keep","vendor":{"a":1}}]}""")
            vendorId = store.inspect(id) { document ->
                document.startTransaction().use { tx ->
                    val vendor = tx.set(ObjectId.ROOT, "vendor", ObjectType.MAP)
                    tx.set(vendor, "bytes", byteArrayOf(0, 1, -1))
                    tx.set(vendor, "nested", ObjectType.LIST).also { tx.insert(it, 0, "opaque") }
                    tx.set(ObjectId.ROOT, "vendorMetadata", opaqueMetadata)
                    tx.commit()
                    vendor
                }
            }
            textId = store.inspect(id) { (it.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id }
            val before = assertNotNull(store.read(id))
            board = before.sceneJson
            val heads = store.inspect(id) { it.heads }
            assertTrue(store.replaceMarkdown(id, before.markdown, "😀 edited e\u0301"))
            assertEquals(before.copy(markdown = "😀 edited e\u0301"), store.read(id))
            store.inspect(id) { document ->
                val patches = document.diff(heads, document.heads)
                assertTrue(patches.isNotEmpty())
                assertTrue(patches.all { it.obj == textId }, "the only edited object is the existing Markdown Text")
            }
            assertTrue(store.persistCanvas(id), "the edited note reaches durable storage before close")
        }
        NotebookLocalStore(directory, "markdown-restart-peer").use { store ->
            val note = assertNotNull(store.read(id))
            assertEquals("😀 edited e\u0301", note.markdown)
            assertEquals("Keep title", note.title)
            assertEquals(board, note.sceneJson)
            assertEquals(listOf(item), note.items)
            assertEquals(listOf(id), store.listDocuments())
            assertNull(store.canvasDocument(id))
            store.inspect(id) { document ->
                assertEquals(textId, (document.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id)
                assertEquals(vendorId, (document.get(ObjectId.ROOT, "vendor").orElseThrow() as AmValue.Map).id)
                assertContentEquals(byteArrayOf(0, 1, -1), (document.get(vendorId, "bytes").orElseThrow() as AmValue.Bytes).value)
                val nested = (document.get(vendorId, "nested").orElseThrow() as AmValue.List).id
                assertEquals("opaque", (document.get(nested, 0).orElseThrow() as AmValue.Str).value)
                assertEquals(opaqueMetadata, (document.get(ObjectId.ROOT, "vendorMetadata").orElseThrow() as AmValue.Str).value)
            }
        }
    }

    @Test
    fun concurrentEditsWithTheSameExpectedTextHaveOnlyOneWinner() = withNote { store, id ->
        store.insertMarkdown(id, 0, "original")
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val edits = listOf("first", "second").map { replacement ->
                CompletableFuture.supplyAsync({
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    replacement to store.replaceMarkdown(id, "original", replacement)
                }, executor)
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val results = edits.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.second })
            assertEquals(results.single { it.second }.first, store.read(id)?.markdown)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun peerChangesMergedBeforeTheEditRejectStaleTextAndLaterMergesRetainConcurrentEdits() = withNote { store, id ->
        store.insertMarkdown(id, 0, "prefix old suffix")
        val peer = store.inspect(id) { it.fork() }
        try {
            val peerText = (peer.get(ObjectId.ROOT, "markdown").orElseThrow() as AmValue.Text).id
            peer.startTransaction().use { tx ->
                tx.spliceText(peerText, 0, 0, "remote ")
                tx.commit()
            }
            assertTrue(store.replaceMarkdown(id, "prefix old suffix", "prefix new suffix"))
            store.inspect(id) { it.merge(peer) }
            assertEquals("remote prefix new suffix", store.read(id)?.markdown)
            val before = store.inspect(id) { it.heads }
            assertFalse(store.replaceMarkdown(id, "prefix new suffix", "lost remote edit"))
            assertContentEquals(before, store.inspect(id) { it.heads })
            assertEquals("remote prefix new suffix", store.read(id)?.markdown)
        } finally {
            peer.free()
        }
    }

    private fun withNote(block: (NotebookLocalStore, DocumentId) -> Unit) {
        NotebookLocalStore(Files.createTempDirectory("notebook-markdown-edit-"), "markdown-edit-peer").use { store ->
            val id = store.create("Note")
            assertNull(store.canvasDocument(id))
            block(store, id)
        }
    }

    private fun <T> NotebookLocalStore.inspect(id: DocumentId, block: (Document) -> T): T =
        assertNotNull(open(id)).withDocument(block).get(10, TimeUnit.SECONDS)
}
