package com.letta.mobile.desktop.data

import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopIndexedTimelineFilesTest {
    private fun entry(order: Long, identity: String = "id", bytes: ByteArray = byteArrayOf(1)) =
        DesktopIndexedTimelineFiles.Entry(DesktopIndexedTimelineFiles.Key(order, identity),
            byteArrayOf(9), bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    private fun temporary(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("indexed-timeline-test")
        try { return block(root) } finally { root.toFile().deleteRecursively() }
    }

    @Test fun boundedSeekAcross28kRowsAndSignedTieBreaks() = temporary { root ->
        val store = DesktopIndexedTimelineFiles(root)
        val snapshot = store.publish(sequence {
            yield(entry(Long.MIN_VALUE))
            repeat(28_000) { yield(entry(it.toLong() / 2, if (it % 2 == 0) "a" else "b")) }
            yield(entry(Long.MAX_VALUE))
        })
        val rows = snapshot.seek(DesktopIndexedTimelineFiles.Key(7000, "a"), 40,
            DesktopIndexedTimelineFiles.Direction.AFTER)
        assertEquals(40, rows.size)
        assertEquals(DesktopIndexedTimelineFiles.Key(7000, "b"), rows.first().key)
        assertTrue(snapshot.indexBytesRead < 100_000, "Binary seek must not scan the index")
        val before = snapshot.seek(DesktopIndexedTimelineFiles.Key(Long.MIN_VALUE, "id"), 40,
            DesktopIndexedTimelineFiles.Direction.BEFORE)
        assertTrue(before.isEmpty())
        assertEquals(snapshot.generation, DesktopIndexedTimelineFiles(root).open()!!.generation)
    }

    @Test fun boundedTailIncludesMaximumKeyAndHandlesEmptyGeneration() = temporary { root ->
        val store = DesktopIndexedTimelineFiles(root)
        assertTrue(store.publish(emptySequence()).tail(40).isEmpty())
        val snapshot = store.publish(sequence {
            repeat(28_000) { yield(entry(it.toLong())) }
            yield(entry(Long.MAX_VALUE, "\uffff".repeat(256)))
        })
        val rows = snapshot.tail(40)
        assertEquals(40, rows.size)
        assertEquals(Long.MAX_VALUE, rows.last().key.order)
        assertEquals("\uffff".repeat(256), rows.last().key.identity)
        assertTrue(snapshot.indexBytesRead < 70_000, "Tail must read only the requested rows")
        assertFailsWith<IllegalArgumentException> { snapshot.tail(0) }
        assertFailsWith<IllegalArgumentException> { snapshot.tail(257) }
    }

    @Test fun exactChunkedBytesAndCorruptionDetection() = temporary { root ->
        val bytes = ByteArray(200_003) { (it % 251).toByte() }
        val snapshot = DesktopIndexedTimelineFiles(root).publish(sequenceOf(entry(1, bytes = bytes)))
        val row = snapshot.seek(DesktopIndexedTimelineFiles.Key(0, "id"), 1,
            DesktopIndexedTimelineFiles.Direction.AFTER).single()
        assertContentEquals(bytes, snapshot.readBody(row, 0, bytes.size))
        assertContentEquals(bytes.copyOfRange(65_533, 135_533), snapshot.readBody(row, 65_533, 70_000))
        assertContentEquals(byteArrayOf(), snapshot.readBody(row, bytes.size.toLong(), 1))
        RandomAccessFile(root.resolve("${snapshot.generation}.body").toFile(), "rw").use {
            it.seek(40)
            it.writeByte(99)
        }
        assertFailsWith<IllegalArgumentException> { snapshot.readBody(row, 0, 1) }
    }

    @Test fun everyCancellationCheckpointPreservesActiveAndFallback() = temporary { root ->
        val store = DesktopIndexedTimelineFiles(root)
        val fallback = store.publish(sequenceOf(entry(0)))
        val active = store.publish(sequenceOf(entry(1)))
        var checkpoints = 0
        val probeRoot = root.resolve("probe")
        DesktopIndexedTimelineFiles(probeRoot).publish(sequenceOf(entry(2, bytes = ByteArray(150_000)))) {
            checkpoints++
        }
        repeat(checkpoints) { failAt ->
            var visited = 0
            assertFailsWith<CancellationException> {
                store.publish(sequenceOf(entry(2, bytes = ByteArray(150_000)))) {
                    if (visited++ == failAt) throw CancellationException("interrupted")
                }
            }
            assertEquals(active.generation, DesktopIndexedTimelineFiles(root).open()!!.generation)
        }
        // Incomplete active index falls back without reading or decoding the body corpus.
        RandomAccessFile(root.resolve("${active.generation}.index").toFile(), "rw").use { it.setLength(0) }
        assertEquals(fallback.generation, DesktopIndexedTimelineFiles(root).open()!!.generation)
        val next = store.publish(sequenceOf(entry(3)))
        RandomAccessFile(root.resolve("${next.generation}.index").toFile(), "rw").use { it.setLength(0) }
        assertEquals(fallback.generation, store.open()!!.generation)
    }

    @Test fun truncatedBodyFallsBackBeforeReturningStartupSnapshot() = temporary { root ->
        val store = DesktopIndexedTimelineFiles(root)
        val fallback = store.publish(sequenceOf(entry(0)))
        val active = store.publish(sequenceOf(entry(1, bytes = ByteArray(150_000))))
        RandomAccessFile(root.resolve("${active.generation}.body").toFile(), "rw").use {
            it.setLength(it.length() - 1)
        }
        assertEquals(fallback.generation, DesktopIndexedTimelineFiles(root).open()!!.generation)
    }

    @Test fun invalidInputNeverPublishesAndReadersKeepOldGeneration() = temporary { root ->
        val store = DesktopIndexedTimelineFiles(root)
        val old = store.publish(sequenceOf(entry(1)))
        assertFailsWith<IllegalArgumentException> {
            store.publish(sequenceOf(entry(3), entry(2)))
        }
        val short = entry(2).copy(bodyBytes = 2)
        assertFailsWith<IllegalArgumentException> { store.publish(sequenceOf(short)) }
        val long = entry(2).copy(bodyBytes = 0)
        assertFailsWith<IllegalArgumentException> { store.publish(sequenceOf(long)) }
        assertEquals(old.generation, store.open()!!.generation)
        store.publish(sequenceOf(entry(2)))
        val row = old.seek(DesktopIndexedTimelineFiles.Key(0, "id"), 1,
            DesktopIndexedTimelineFiles.Direction.AFTER).single()
        assertContentEquals(byteArrayOf(1), old.readBody(row, 0, 1))
        assertFailsWith<IllegalArgumentException> { store.open()!!.readBody(row, 0, 1) }
        assertFailsWith<IllegalArgumentException> {
            old.seek(row.key, 257, DesktopIndexedTimelineFiles.Direction.AFTER)
        }
    }

    @Test fun sameGenerationPointerSubstitutionFailsClosed() = temporary { root ->
        val snapshot = DesktopIndexedTimelineFiles(root).publish(sequenceOf(
            entry(1, bytes = byteArrayOf(1)), entry(2, bytes = byteArrayOf(2)),
        ))
        val (first, second) = snapshot.tail(2)
        assertFailsWith<IllegalArgumentException> {
            snapshot.readBody(first.copy(bodyOffset = second.bodyOffset), 0, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            snapshot.readBody(first.copy(ordinal = second.ordinal, bodyOffset = second.bodyOffset), 0, 1)
        }
        assertContentEquals(byteArrayOf(1), snapshot.readBody(first, 0, 1))
    }

    @Test fun utf16TieBreakSurvivesDiskRoundTrip() = temporary { root ->
        val identities = listOf("a", "\ud7ff", "\ud800", "\ud800\udc00", "\udfff", "\ue000", "\uffff")
        val snapshot = DesktopIndexedTimelineFiles(root).publish(identities.asSequence().map { entry(1, it) })
        assertEquals(identities, snapshot.tail(20).map { it.key.identity })
        assertEquals(identities.drop(3), snapshot.seek(DesktopIndexedTimelineFiles.Key(1, "\ud800"),
            20, DesktopIndexedTimelineFiles.Direction.AFTER).map { it.key.identity })
    }

    @Test fun indexCorruptionAndReadCancellationFailClosed() = temporary { root ->
        val store = DesktopIndexedTimelineFiles(root)
        val snapshot = store.publish(sequenceOf(entry(1, bytes = ByteArray(150_000))))
        val row = snapshot.seek(DesktopIndexedTimelineFiles.Key(0, "id"), 1,
            DesktopIndexedTimelineFiles.Direction.AFTER).single()
        assertFailsWith<CancellationException> {
            snapshot.readBody(row, 0, 150_000) { throw CancellationException() }
        }
        RandomAccessFile(root.resolve("${snapshot.generation}.index").toFile(), "rw").use {
            it.seek(32)
            it.writeByte(99)
        }
        assertFailsWith<IllegalArgumentException> {
            snapshot.seek(DesktopIndexedTimelineFiles.Key(0, "id"), 1,
                DesktopIndexedTimelineFiles.Direction.AFTER)
        }
    }
}
