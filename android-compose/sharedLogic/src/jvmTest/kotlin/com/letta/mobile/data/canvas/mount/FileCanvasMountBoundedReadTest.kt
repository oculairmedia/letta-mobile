package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.hoststate.DurableHostFile
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileCanvasMountBoundedReadTest {
    private val agent = AgentId("a")
    private val host = HostId("host")

    @Test
    fun absentReadsCreateNothingAndLeaveExistingProductStateUnchanged() = runBlocking {
        val root = Files.createTempDirectory("mount-read-absence")
        val sentinel = root.resolve("existing-state")
        Files.writeString(sentinel, "do not change")
        val modified = Files.getLastModifiedTime(root)
        val store = FileCanvasMountStore(root, host)
        repeat(512) { index ->
            assertNull(store.readBinding(agent, ConversationId("absent-$index")))
            assertNull(store.readDefault(AgentId("absent-$index")))
        }
        assertEquals(listOf("existing-state"), Files.list(root).use { it.map { path -> path.fileName.toString() }.toList() })
        assertEquals("do not change", Files.readString(sentinel))
        assertEquals(modified, Files.getLastModifiedTime(root))
        assertEquals(0, owners(root))
    }

    @Test
    fun lockFilesAndRuntimeOwnersAreBoundedAndAbsentReadsDoNotGrowThem() = runBlocking {
        val root = Files.createTempDirectory("mount-bounded-locks")
        val store = FileCanvasMountStore(root, host)
        repeat(768) { index -> store.bindIfAbsent(agent, ConversationId("c-$index"), CanvasId("board-$index")) }
        val locks = lockFiles(root)
        val count = owners(root)
        assertTrue(locks.size in 1..256, "The lock file population must be bounded by the stripe count")
        assertTrue(count in 1..256, "Runtime owners must be per stripe, not per record")
        repeat(512) { index ->
            assertNull(store.readBinding(agent, ConversationId("missing-$index")))
            assertNull(store.readDefault(AgentId("missing-$index")))
        }
        assertEquals(locks, lockFiles(root))
        assertEquals(count, owners(root))
    }

    @Test
    fun collidingAndIndependentStripesRetainWinnersAndUpdatesAcrossInstances() = runBlocking {
        val groups = (0..512).map { ConversationId("c-$it") }.groupBy(::stripe).values
        val pairs = listOf(groups.first { it.size >= 2 }.take(2), groups.take(2).map { it.first() })
        pairs.forEachIndexed { pairIndex, keys ->
            val root = Files.createTempDirectory("mount-stripe-races")
            val results = keys.flatMap { key ->
                (0..9).map { proposal ->
                    async(Dispatchers.Default) {
                        FileCanvasMountStore(root, host).bindIfAbsent(agent, key, CanvasId("proposal-$proposal")).durable()
                    }
                }
            }.awaitAll()
            results.chunked(10).forEachIndexed { index, winners ->
                assertEquals(1, winners.distinct().size)
                assertEquals(winners.first(), FileCanvasMountStore(root, host).readBinding(agent, keys[index]))
            }
            keys.mapIndexed { index, key ->
                async(Dispatchers.Default) { FileCanvasMountStore(root, host).replaceBinding(agent, key, CanvasId("update-$index")) }
            }.awaitAll()
            keys.forEachIndexed { index, key ->
                assertEquals(CanvasId("update-$index"), FileCanvasMountStore(root, host).readBinding(agent, key))
            }
            assertEquals(pairIndex + 1, lockFiles(root).size, "Same stripe serializes; distinct stripes retain separate owners")
            assertEquals(pairIndex + 1, owners(root))
        }
    }

    @Test
    fun aRecordWhoseLockEntriesWereLostIsStillReadAndWritable() = runBlocking {
        // After a power cut on a host that cannot confirm directory entries (Windows), a record can
        // survive while its stripe lock file, or the whole lock directory, does not.
        val root = Files.createTempDirectory("mount-lost-locks")
        val conversation = ConversationId("kept")
        FileCanvasMountStore(root, host).bindIfAbsent(agent, conversation, CanvasId("board"))
        val locks = root.resolve("canvas-mounts").resolve("locks")
        lockFiles(root).forEach(Files::delete)
        assertEquals(CanvasId("board"), FileCanvasMountStore(root, host).readBinding(agent, conversation))
        lockFiles(root).forEach(Files::delete)
        Files.delete(locks)
        assertEquals(CanvasId("board"), FileCanvasMountStore(root, host).readBinding(agent, conversation))
        FileCanvasMountStore(root, host).replaceBinding(agent, conversation, CanvasId("next"))
        assertEquals(CanvasId("next"), FileCanvasMountStore(root, host).readBinding(agent, conversation))
        // Recovery is for existing records only: an absent key still leaves nothing behind.
        val before = lockFiles(root)
        assertNull(FileCanvasMountStore(root, host).readBinding(agent, ConversationId("never-written")))
        assertEquals(before, lockFiles(root))
    }

    @Test
    fun oversizedRecordIsRejectedBeforeDecodingAndWithoutResettingIt() = runBlocking {
        val root = Files.createTempDirectory("mount-record-bound")
        val store = FileCanvasMountStore(root, host)
        store.setDefault(agent, CanvasId("initial"))
        val record = Files.list(root.resolve("canvas-mounts")).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.toList().single()
        }
        val fields = Json.parseToJsonElement(Files.readString(record)).jsonObject
        val oversized = JsonObject(fields + ("canvas" to JsonPrimitive("x".repeat(8_192)))).toString()
        Files.writeString(record, oversized)
        val failure = assertFailsWith<IllegalArgumentException> { store.readDefault(agent) }
        assertEquals("Mount record is too large", failure.message)
        assertEquals(oversized, Files.readString(record))
        assertIs<IllegalArgumentException>(store.setDefault(AgentId("new"), CanvasId("x".repeat(8_192))).notAppliedCause())
        assertNull(store.readDefault(AgentId("new")))
    }

    private fun owners(root: Path): Int = DurableHostFile(root.resolve("counter")) { }.lockOwnerCount()

    private fun lockFiles(root: Path): Set<Path> = Files.walk(root).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".lock") }.toList().toSet()
    }

    // Independent first-byte SHA-256 oracle for the fixed fixture ids, not a production hash helper.
    private fun stripe(conversation: ConversationId): Int = MessageDigest.getInstance("SHA-256")
        .digest("[\"a\",\"${conversation.value}\"]".encodeToByteArray()).first().toInt() and 255
}
