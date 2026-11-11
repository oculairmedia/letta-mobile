package com.letta.mobile.data.controller.node.iroh.records

import com.letta.mobile.data.controller.node.iroh.errors.AdminRpcClientError
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileWrapperRecordStoreTest {
    private val epoch = Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()
    private val day = 86_400_000L

    private class TemporaryDirectory : AutoCloseable {
        val path: Path = Files.createTempDirectory("wrapper-record-store-test")
        override fun close() { path.toFile().deleteRecursively() }
    }

    private fun run(name: String, at: Long = epoch) = RecordedRunInput(
        "agent-A", "task-A", name, at, RecordedRunStatus.DONE, "operator requested", RecordedRunSource.HUMAN,
    )

    private fun lease(id: String, state: RecordedLeaseState = RecordedLeaseState.ACTIVE) =
        RecordedLeaseInput(id, "agent-A", "work", epoch, epoch + day, state)

    @Test
    fun startupAndWriteRetentionUseInjectedClockAndPersistPruning() {
        for (startup in listOf(true, false)) TemporaryDirectory().use { dir ->
            var now = epoch
            val path = dir.path.resolve("records.json")
            val initial = FileWrapperRecordStore(path, clock = { now })
            initial.record(run("old"))
            initial.record(run("inside", epoch + 2 * day))
            initial.upsert(lease("closed", RecordedLeaseState.RECOVERED))
            initial.upsert(lease("active"))
            now += 91 * day
            val current = if (startup) FileWrapperRecordStore(path, clock = { now }) else initial.also {
                it.record(run("new", now))
            }
            val expected = if (startup) setOf("inside") else setOf("inside", "new")
            assertEquals(expected, current.snapshot().runs.map { it.input.scheduleName }.toSet())
            assertEquals(listOf("active"), current.snapshot().leases.map { it.input.id })
            val reopened = FileWrapperRecordStore(path, clock = { now })
            assertEquals(current.snapshot(), reopened.snapshot(), "retention must reach disk")
        }
    }

    @Test
    fun retentionBoundariesAreInclusiveAndUnknownLeasesSurvive() {
        var now = epoch
        val store = FileWrapperRecordStore(clock = { now })
        store.record(run("boundary"))
        store.upsert(lease("closed", RecordedLeaseState.DONE))
        store.upsert(lease("unknown", RecordedLeaseState.UNKNOWN))
        now += 30 * day
        store.record(run("trigger-30", now))
        assertEquals(setOf("closed", "unknown"), store.snapshot().leases.map { it.input.id }.toSet())
        now += 60 * day
        store.record(run("trigger-90", now))
        assertTrue(store.snapshot().runs.any { it.input.scheduleName == "boundary" })
        assertEquals(listOf("unknown"), store.snapshot().leases.map { it.input.id })
        now++
        store.record(run("trigger-after", now))
        assertTrue(store.snapshot().runs.none { it.input.scheduleName == "boundary" })
    }

    @Test
    fun leaseUpdatesKeepFirstSeenAndUseLastUpsertClock() = TemporaryDirectory().use { dir ->
        var now = epoch
        val path = dir.path.resolve("records.json")
        val store = FileWrapperRecordStore(path, clock = { now })
        store.upsert(lease("lease-1"))
        now += day
        val recovered = store.upsert(lease("lease-1", RecordedLeaseState.RECOVERED).copy(dueAtMs = epoch + 2 * day))
        assertEquals(epoch, recovered.createdAtMs)
        assertEquals(epoch + day, recovered.updatedAtMs)
        assertEquals(RecordedLeaseState.RECOVERED, recovered.input.state)
        assertEquals(listOf(recovered), FileWrapperRecordStore(path, clock = { now }).snapshot().leases)
        assertFailsWith<IllegalArgumentException> { store.upsert(lease("lease-1").copy(owner = "agent-B")) }
        assertFailsWith<IllegalArgumentException> { store.upsert(lease("lease-1").copy(startedAtMs = epoch + 1)) }
        assertEquals(listOf(recovered), store.snapshot().leases)
    }

    @Test
    fun retriesAreIdempotentAndChangesConflictWithoutReplacingHistory() = TemporaryDirectory().use { dir ->
        val path = dir.path.resolve("records.json")
        val store = FileWrapperRecordStore(path, clock = { epoch })
        val first = store.record(run("daily"))
        assertEquals(first, store.record(run("daily")))
        assertEquals(first, FileWrapperRecordStore(path, clock = { epoch + day }).record(run("daily")))
        val conflict = assertFailsWith<IllegalArgumentException> { store.record(run("daily").copy(reason = "changed")) }
        assertEquals("validation.conflict: run identity", conflict.message)
        assertEquals(listOf(first), store.snapshot().runs)
    }

    @Test
    fun malformedValuesAndPastDueOrderStoreNothing() {
        val store = FileWrapperRecordStore(clock = { epoch })
        val invalidRuns = listOf(
            run("valid").copy(agentId = ""), run("valid").copy(scheduleId = "x".repeat(257)),
            run("valid").copy(scheduleId = "unsafe/task"), run("valid").copy(scheduleId = "unsafe\\task"),
            run("valid").copy(scheduleId = "unsafe\u0000task"), run("valid").copy(scheduleId = "\ufeff"),
            run("x".repeat(513)), run("valid").copy(reason = "x".repeat(2049)),
            run("valid").copy(detail = "x".repeat(8193)), run("valid").copy(instantMs = 0),
            run("valid").copy(instantMs = epoch + 367 * day),
        )
        invalidRuns.forEach {
            assertTrue(assertFailsWith<IllegalArgumentException> { store.record(it) }.message!!.startsWith("validation.invalid_params:"))
        }
        val invalidLeases = listOf(
            lease(""), lease("a").copy(owner = " "), lease("a").copy(label = ""),
            lease("a").copy(dueAtMs = epoch - 1), lease("a").copy(startedAtMs = 0),
            lease("a").copy(runId = "x".repeat(257)), lease("a").copy(detail = "x".repeat(8193)),
        )
        invalidLeases.forEach {
            assertTrue(assertFailsWith<IllegalArgumentException> { store.upsert(it) }.message!!.startsWith("validation.invalid_params:"))
        }
        assertEquals(WrapperRecordSnapshot(), store.snapshot())
    }

    @Test
    fun enumNamesPersistAndReloadWithoutOrdinals() = TemporaryDirectory().use { dir ->
        val path = dir.path.resolve("records.json")
        val store = FileWrapperRecordStore(path, clock = { epoch })
        store.record(run("unknown").copy(status = RecordedRunStatus.UNKNOWN, source = RecordedRunSource.UNKNOWN))
        store.upsert(lease("unknown", RecordedLeaseState.UNKNOWN))
        val document = Json.parseToJsonElement(Files.readString(path)).jsonObject
        val run = document.getValue("runs").jsonArray.single().jsonObject.getValue("input").jsonObject
        val lease = document.getValue("leases").jsonArray.single().jsonObject.getValue("input").jsonObject
        assertEquals(JsonPrimitive("unknown"), run["status"])
        assertEquals(JsonPrimitive("unknown"), run["source"])
        assertEquals(JsonPrimitive("unknown"), lease["state"])
        assertEquals(store.snapshot(), FileWrapperRecordStore(path, clock = { epoch }).snapshot())
    }

    @Test
    fun encodedRecordByteCapAppliesEvenWhenIndividualStringBoundsPass() {
        val store = FileWrapperRecordStore(clock = { epoch })
        val oversized = run("\u0001".repeat(512)).copy(
            agentId = "\u0001".repeat(256), scheduleId = "\u0001".repeat(256),
            reason = "\u0001".repeat(2048), detail = "\u0001".repeat(8192),
        )
        assertEquals("validation.invalid_params: record size", assertFailsWith<IllegalArgumentException> {
            store.record(oversized)
        }.message)
        assertEquals(WrapperRecordSnapshot(), store.snapshot())
    }

    @Test
    fun activeLeaseOverflowRejectsNewOrReopenedLeaseButAllowsCloseAndUpdate() {
        val store = FileWrapperRecordStore(clock = { epoch })
        store.upsert(lease("closed", RecordedLeaseState.DONE))
        repeat(1000) { store.upsert(lease("active-$it")) }
        assertEquals("storage.capacity: active leases", assertFailsWith<AdminRpcClientError> { store.upsert(lease("overflow")) }.message)
        assertFailsWith<AdminRpcClientError> { store.upsert(lease("closed")) }
        store.upsert(lease("active-0").copy(label = "updated"))
        store.upsert(lease("active-0", RecordedLeaseState.DONE))
        store.upsert(lease("closed"))
        val active = store.snapshot().leases.count {
            it.input.state == RecordedLeaseState.ACTIVE || it.input.state == RecordedLeaseState.UNKNOWN
        }
        assertEquals(1000, active)
    }

    @Test
    fun corruptPartialStoreIsQuarantinedWithWarningAndStartupContinues() = TemporaryDirectory().use { dir ->
        val path = dir.path.resolve("records.json")
        Files.writeString(path, "{partial")
        var warnings = 0
        val store = FileWrapperRecordStore(path, clock = { epoch }, warn = { warnings++ })
        assertEquals(1, warnings)
        assertEquals(WrapperRecordSnapshot(), store.snapshot())
        val quarantined = Files.list(dir.path).use { it.toList() }.single()
        assertTrue(quarantined.fileName.toString().startsWith("records.json.corrupt-"))
        assertEquals("{partial", Files.readString(quarantined))
        store.record(run("after-corruption"))
        assertEquals(1, FileWrapperRecordStore(path, clock = { epoch }).snapshot().runs.size)
    }

    @Test
    fun directoryPathIsNeverQuarantinedOrMoved() = TemporaryDirectory().use { dir ->
        val path = dir.path.resolve("records.json")
        Files.createDirectory(path)
        Files.writeString(path.resolve("sentinel"), "keep")
        val store = FileWrapperRecordStore(path, clock = { epoch }, warn = {})
        assertTrue(Files.isDirectory(path))
        assertEquals("keep", Files.readString(path.resolve("sentinel")))
        assertEquals(WrapperRecordSnapshot(), store.snapshot())
        assertFailsWith<java.io.IOException> { store.record(run("blocked")) }
        assertEquals("keep", Files.readString(path.resolve("sentinel")))
    }

    @Test
    fun failedAtomicWriteLeavesCommittedMemoryAndNoTemporaryFile() = TemporaryDirectory().use { dir ->
        val path = dir.path.resolve("blocked")
        val store = FileWrapperRecordStore(path, clock = { epoch })
        Files.createDirectory(path)
        assertFailsWith<java.io.IOException> { store.record(run("not-committed")) }
        assertEquals(WrapperRecordSnapshot(), store.snapshot())
        assertEquals(listOf(path), Files.list(dir.path).use { it.toList() })
    }

    @Test
    fun simultaneouslyReleasedWriterThreadsLoseNoRunsOrLeasesOnDisk() = TemporaryDirectory().use { dir ->
        val path = dir.path.resolve("records.json")
        val store = FileWrapperRecordStore(path, clock = { epoch })
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val jobs = (0 until 48).map { index -> executor.submit {
                ready.countDown()
                assertTrue(start.await(10, TimeUnit.SECONDS))
                store.record(run("concurrent-$index"))
                store.upsert(lease("lease-$index"))
            } }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "eight actual writer threads must reach the barrier")
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
        val snapshot = FileWrapperRecordStore(path, clock = { epoch }).snapshot()
        assertEquals((0 until 48).map { "concurrent-$it" }.toSet(), snapshot.runs.map { it.input.scheduleName }.toSet())
        assertEquals((0 until 48).map { "lease-$it" }.toSet(), snapshot.leases.map { it.input.id }.toSet())
        assertEquals(store.snapshot(), snapshot)
    }
}
