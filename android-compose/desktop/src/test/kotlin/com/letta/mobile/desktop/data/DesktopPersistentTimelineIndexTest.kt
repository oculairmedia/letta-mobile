package com.letta.mobile.desktop.data

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DesktopPersistentTimelineIndexTest {
    @TempDir lateinit var directory: Path
    private fun key(value: Int) = ByteBuffer.allocate(4).putInt(value xor Int.MIN_VALUE).array()
    private fun budget() = DesktopPersistentTimelineIndex.Budget()

    @Test fun pathCopiesPreserveOldRootsAndRankAcrossRotationsAndDeletes() {
        val index = DesktopPersistentTimelineIndex(directory)
        var root: String? = null
        val oracle = TreeMap<Int, ByteArray>()
        val random = Random(71)
        repeat(200) {
            val id = random.nextInt(-200, 200)
            val oldRoot = root
            val oldValue = index.get(root, key(id), budget())
            if (random.nextBoolean()) {
                val bytes = key(it)
                root = index.put(root, key(id), bytes, budget())
                oracle[id] = bytes
            } else {
                root = index.delete(root, key(id), budget())
                oracle.remove(id)
            }
            assertContentEquals(oldValue, index.get(oldRoot, key(id), budget()))
            assertEquals(oracle.size.toLong(), index.count(root, budget()))
            assertNull(index.select(root, oracle.size.toLong(), budget()))
            val absent = random.nextInt(-220, 220)
            assertEquals(oracle.headMap(absent).size.toLong(), index.rank(root, key(absent), budget()))
            if (it % 50 == 0 || it == 199) oracle.entries.forEachIndexed { ordinal, entry ->
                assertEquals(ordinal.toLong(), index.rank(root, key(entry.key), budget()))
                val selected = assertNotNull(index.select(root, ordinal.toLong(), budget()))
                assertContentEquals(key(entry.key), selected.key)
                assertContentEquals(entry.value, selected.value)
            }
        }
        oracle.keys.toList().forEach { root = index.delete(root, key(it), budget()) }
        assertNull(root)
    }

    @Test fun publicationIsAtomicAndRetainsIndependentRootsAndExactObjects() {
        val index = DesktopPersistentTimelineIndex(directory)
        val body = index.putObject(byteArrayOf(0, -1, 3), budget())
        val order = index.put(null, key(10), body.toByteArray(), budget())
        val identity = index.put(null, key(20), key(10), budget())
        val evidence = index.put(null, key(30), byteArrayOf(5), budget())
        val roots = DesktopPersistentTimelineIndex.Roots(order, identity, evidence)
        index.publish(roots, byteArrayOf(1), budget())
        val changed = index.put(order, key(Int.MIN_VALUE), byteArrayOf(9), budget())
        assertFailsWith<IllegalStateException> {
            index.publish(roots.copy(order = changed), byteArrayOf(2), budget()) { error("cancel") }
        }
        assertEquals(roots, DesktopPersistentTimelineIndex(directory).open()?.roots)
        index.publish(roots.copy(order = changed), byteArrayOf(2), budget())
        val reopened = DesktopPersistentTimelineIndex(directory)
        val head = assertNotNull(reopened.open())
        val previous = reopened.openHead(assertNotNull(head.previous))
        assertEquals(roots, previous.roots)
        assertContentEquals(byteArrayOf(1), previous.checkpoint)
        assertContentEquals(byteArrayOf(2), head.checkpoint)
        assertContentEquals(byteArrayOf(0, -1, 3), reopened.objectBytes(body, 3, budget()))
        assertContentEquals(body.toByteArray(), reopened.get(head.roots.order, key(10), budget()))
        assertEquals(identity, head.roots.identity)
        assertEquals(evidence, head.roots.evidence)
    }

    @Test fun readAndWriteBudgetsFailBeforePublicationAndCorruptionFailsClosed() {
        val index = DesktopPersistentTimelineIndex(directory)
        var root: String? = null
        repeat(128) { root = index.put(root, key(it), key(it), budget()) }
        index.publish(DesktopPersistentTimelineIndex.Roots(order = root), byteArrayOf(1), budget())
        val smallRead = DesktopPersistentTimelineIndex.Budget(DesktopPersistentTimelineIndex.Limits(visitedPages = 1))
        assertFailsWith<IllegalStateException> { index.get(root, key(0), smallRead) }
        val smallWrite = DesktopPersistentTimelineIndex.Budget(DesktopPersistentTimelineIndex.Limits(writtenBytes = 4095))
        assertFailsWith<IllegalStateException> { index.put(root, key(200), key(200), smallWrite) }
        assertEquals(0L, smallWrite.writtenBytes)
        assertEquals(root, index.open()?.roots?.order)
        assertFailsWith<IllegalArgumentException> { index.put(root, ByteArray(1025), byteArrayOf(), budget()) }
        Files.write(directory.resolve(requireNotNull(root)), ByteArray(4096))
        assertFailsWith<IllegalArgumentException> { index.get(root, key(0), budget()) }
        Files.writeString(directory.resolve("active-v2"), "broken")
        assertFailsWith<IllegalArgumentException> { index.open() }
    }

    @Test fun boundedBatchDiscardsIntermediatePagesBeforeDurablePublication() {
        val index = DesktopPersistentTimelineIndex(directory)
        val batch = budget()
        index.beginBatch()
        var root: String? = null
        repeat(100) { root = index.put(root, key(it), key(it), batch) }
        assertEquals(0L, index.work.fileSyncs)
        assertNull(DesktopPersistentTimelineIndex(directory).open())
        index.publish(DesktopPersistentTimelineIndex.Roots(order = root), byteArrayOf(1), batch)
        assertEquals(101L, index.work.fileSyncs)
        assertTrue(index.work.discardedPages > 400)
        assertTrue(index.work.durableBytes < index.work.encodedBytes / 2)
        val reopened = DesktopPersistentTimelineIndex(directory)
        repeat(100) { assertContentEquals(key(it), reopened.get(root, key(it), budget())) }
        index.beginBatch()
        index.put(root, key(101), key(101), budget())
        index.abandonBatch()
        assertEquals(root, reopened.open()?.roots?.order)
        assertNull(reopened.get(root, key(101), budget()))
    }

    @Test fun ordinaryUpdateAndWindowWorkStayBoundedAsHistoryGrows() {
        val index = DesktopPersistentTimelineIndex(directory)
        var root: String? = null
        repeat(2048) { root = index.put(root, key(it), key(it), budget()) }
        val mutation = budget()
        val next = index.put(root, key(1024), byteArrayOf(7), mutation)
        assertTrue(mutation.writtenBytes <= 20L * 4096, "bytes=${mutation.writtenBytes}")
        assertTrue(mutation.visitedPages < 200, "visits=${mutation.visitedPages}")
        val reads = budget()
        val rank = index.rank(next, key(1000), reads)
        repeat(40) { assertNotNull(index.select(next, rank + it, reads)) }
        assertTrue(reads.visitedPages < 1200, "visits=${reads.visitedPages}")
        assertContentEquals(key(1024), index.get(root, key(1024), budget()))
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().matches(Regex("[0-9a-f]{64}")) }
                .forEach { assertEquals(4096L, Files.size(it)) }
        }
    }
}
