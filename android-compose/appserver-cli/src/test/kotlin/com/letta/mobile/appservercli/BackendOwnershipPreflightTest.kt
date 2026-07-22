package com.letta.mobile.appservercli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BackendOwnershipPreflightTest {

    /** Fake liveness: pid -> start time (present = alive). */
    private class FakeLiveness(val table: MutableMap<Long, Long> = mutableMapOf()) : ProcessLiveness {
        override fun startTimeMsOf(pid: Long): Long? = table[pid]
    }

    private fun writeOwner(dir: Path, info: BackendOwnerInfo) {
        Files.writeString(dir.resolve(BackendOwnershipPreflight.OWNER_FILENAME), info.toJson())
    }

    @Test
    fun `no sidecar is Clear`(@TempDir dir: Path) {
        val pf = BackendOwnershipPreflight(FakeLiveness(), selfPid = 100)
        assertEquals(BackendOwnershipPreflight.Decision.Clear, pf.evaluate(dir))
    }

    @Test
    fun `live owner with matching start time is HeldByLiveOwner`(@TempDir dir: Path) {
        val live = FakeLiveness(mutableMapOf(200L to 5_000L))
        writeOwner(dir, BackendOwnerInfo(pid = 200, startTimeMs = 5_000, backendDir = dir.toString(), unit = "u", acquiredAtIso = "t"))
        val pf = BackendOwnershipPreflight(live, selfPid = 100)
        val d = pf.evaluate(dir)
        val held = assertInstanceOf(BackendOwnershipPreflight.Decision.HeldByLiveOwner::class.java, d)
        assertEquals(200L, held.owner.pid)
        assertFalse(d.mayProceed)
    }

    @Test
    fun `dead owner pid is StaleReclaimable`(@TempDir dir: Path) {
        val live = FakeLiveness() // pid 200 not present -> dead
        writeOwner(dir, BackendOwnerInfo(200, 5_000, dir.toString(), "u", "t"))
        val pf = BackendOwnershipPreflight(live, selfPid = 100)
        val d = pf.evaluate(dir)
        assertInstanceOf(BackendOwnershipPreflight.Decision.StaleReclaimable::class.java, d)
        assertTrue(d.mayProceed)
    }

    @Test
    fun `pid reuse (start time mismatch) is StaleReclaimable`(@TempDir dir: Path) {
        val live = FakeLiveness(mutableMapOf(200L to 9_999L)) // alive but different start
        writeOwner(dir, BackendOwnerInfo(200, 5_000, dir.toString(), "u", "t"))
        val pf = BackendOwnershipPreflight(live, selfPid = 100)
        val d = pf.evaluate(dir) as BackendOwnershipPreflight.Decision.StaleReclaimable
        assertTrue(d.reason.contains("reused"))
    }

    @Test
    fun `self owner is HeldBySelf`(@TempDir dir: Path) {
        writeOwner(dir, BackendOwnerInfo(100, 5_000, dir.toString(), "u", "t"))
        val pf = BackendOwnershipPreflight(FakeLiveness(mutableMapOf(100L to 5_000L)), selfPid = 100)
        val d = pf.evaluate(dir)
        assertInstanceOf(BackendOwnershipPreflight.Decision.HeldBySelf::class.java, d)
        assertTrue(d.mayProceed)
    }

    @Test
    fun `malformed sidecar is StaleReclaimable`(@TempDir dir: Path) {
        Files.writeString(dir.resolve(BackendOwnershipPreflight.OWNER_FILENAME), "{garbage")
        val pf = BackendOwnershipPreflight(FakeLiveness(), selfPid = 100)
        val d = pf.evaluate(dir) as BackendOwnershipPreflight.Decision.StaleReclaimable
        assertTrue(d.reason.contains("malformed"))
    }

    @Test
    fun `acquire fences against a live competing writer`(@TempDir dir: Path) {
        val live = FakeLiveness(mutableMapOf(200L to 5_000L))
        writeOwner(dir, BackendOwnerInfo(200, 5_000, dir.toString(), "u", "t"))
        val pf = BackendOwnershipPreflight(live, selfPid = 100)
        assertThrows(BackendCompetingWriterException::class.java) { pf.acquire(dir.toString()) }
    }

    @Test
    fun `acquire reclaims a stale root and writes fresh owner`(@TempDir dir: Path) {
        writeOwner(dir, BackendOwnerInfo(200, 5_000, dir.toString(), "old", "t")) // pid 200 dead
        val live = FakeLiveness(mutableMapOf(100L to 7_000L)) // self is alive
        val pf = BackendOwnershipPreflight(live, selfPid = 100)
        pf.acquire(dir.toString(), unit = "meridian-appserver.service").use { owned ->
            assertEquals(100L, owned.info.pid)
            assertEquals("meridian-appserver.service", owned.info.unit)
            // Sidecar now names us.
            val onDisk = BackendOwnerInfo.fromJson(
                Files.readString(dir.resolve(BackendOwnershipPreflight.OWNER_FILENAME)),
            )
            assertEquals(100L, onDisk!!.pid)
        }
        // Clean close removes the sidecar (no stale metadata).
        assertFalse(Files.exists(dir.resolve(BackendOwnershipPreflight.OWNER_FILENAME)))
    }

    @Test
    fun `acquire on clear root then second acquire fences via lock`(@TempDir dir: Path) {
        val live = FakeLiveness(mutableMapOf(100L to 7_000L))
        val pf = BackendOwnershipPreflight(live, selfPid = 100)
        pf.acquire(dir.toString()).use {
            // A second preflight in THIS process: evaluate() sees HeldBySelf (same
            // pid) and mayProceed, but the FileLock is still held -> acquire must
            // fail closed on the lock.
            assertThrows(BackendAlreadyOwnedException::class.java) { pf.acquire(dir.toString()) }
        }
    }
}
