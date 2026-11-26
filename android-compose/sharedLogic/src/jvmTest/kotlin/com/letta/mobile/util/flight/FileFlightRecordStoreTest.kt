package com.letta.mobile.util.flight

import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import com.letta.mobile.util.attr
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** The on-disk flight record: bounded, survives a restart in order, keeps the newest, and is written off the caller's thread. */
class FileFlightRecordStoreTest {
    private val dir: File = Files.createTempDirectory("flight-record").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun diskBytes(): Long = dir.listFiles().orEmpty().sumOf(File::length)

    @Test
    fun theFilesStayWithinTheirCeilingUnderAFlood() {
        val perFile = 8_192L
        val store = FileFlightRecordStore(dir, perFile)

        (1..20_000).forEach { store.append(listOf("1700000000000 [INFO] FeedTimeline/applied {seq=$it, rows=12}")) }

        assertTrue(diskBytes() <= 2 * perFile, "on disk ${diskBytes()} bytes, ceiling ${2 * perFile}")
        assertEquals(setOf(FileFlightRecordStore.FILE_NAME, "${FileFlightRecordStore.FILE_NAME}.1"), dir.list().orEmpty().toSet())
    }

    @Test
    fun aReopenedRecordReadsThePriorRecordsInOrderAndEvictionKeepsTheNewest() {
        FileFlightRecordStore(dir, 4_096L).append((1..2_000).map { "record %04d".format(it) })

        val reopened = FileFlightRecordStore(dir, 4_096L).read()

        assertEquals("record 2000", reopened.last(), "the newest record survives the restart")
        assertTrue("record 0001" !in reopened, "the oldest were evicted")
        assertEquals((2_001 - reopened.size..2_000).map { "record %04d".format(it) }, reopened, "in order, with no hole")
        FileFlightRecordStore(dir, 4_096L).append(listOf("after restart"))
        assertEquals("after restart", FileFlightRecordStore(dir, 4_096L).read().last())
    }

    @Test
    fun aTelemetryEventIsWrittenOnTheRecordersThreadNotTheCallers() {
        val written = CountDownLatch(1)
        var writerThread: Thread? = null
        val store = object : FlightRecordStore {
            override fun append(lines: List<String>) {
                writerThread = Thread.currentThread()
                written.countDown()
            }
            override fun read(): List<String> = emptyList()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val recorder = TelemetryRecorder()
        val history = Telemetry.installRecorder(recorder)
        val flight = installFileFlightRecordingInto(recorder, store, scope)
        try {
            Telemetry.event("FeedTimeline", "applied", "seq" attr 1)
            assertTrue(written.await(10, TimeUnit.SECONDS), "the record was written")
            assertNotEquals(Thread.currentThread(), writerThread, "the caller's thread never writes the record")
        } finally {
            flight.close()
            history.close()
            scope.cancel()
        }
    }

    /** The production file install's writer context (the I/O dispatcher), over a [store] the test can watch. */
    private fun installFileFlightRecordingInto(recorder: TelemetryRecorder, store: FlightRecordStore, scope: CoroutineScope) =
        FlightRecording.install(recorder, store, scope, "test", Dispatchers.IO)
}
