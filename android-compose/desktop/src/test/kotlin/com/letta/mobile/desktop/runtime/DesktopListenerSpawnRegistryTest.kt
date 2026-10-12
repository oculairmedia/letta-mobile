package com.letta.mobile.desktop.runtime

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-zxk2n.1 (G04): launch-time orphan reaping against a fake process table. */
class DesktopListenerSpawnRegistryTest {
    private val root = createTempDirectory("spawn-registry").toFile()
    private val file = File(root, "listener-spawn-registry.json")
    private val table = FakeTable()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val node = "C:\\app\\letta-code-runtime\\node.exe"
    private val owner = DesktopProcessIdentity(pid = 100, startToken = "2026-10-11T09:00:00Z", commandLine = "java")

    private fun registry(self: DesktopProcessIdentity = owner) =
        DesktopListenerSpawnRegistry(file, table, self = { self })

    /** A previous desktop spawned [pid] and then died without stopping it. */
    private fun leaveOrphan(pid: Long, start: String = "2026-10-11T09:00:05Z") {
        table.processes[owner.pid] = FakeProcess(owner)
        table.processes[pid] = FakeProcess(DesktopProcessIdentity(pid, start, node))
        registry().onSpawned(pid)
        table.processes.remove(owner.pid)
    }

    private val nextLaunch = DesktopProcessIdentity(pid = 200, startToken = "2026-10-11T10:00:00Z", commandLine = "java")

    @Test
    fun aMatchingOrphanIsKilled() {
        leaveOrphan(pid = 4242)

        val report = registry(nextLaunch).reapOrphans()

        assertEquals(listOf(4242L), report.killed)
        assertTrue(table.processes.getValue(4242).killed)
    }

    @Test
    fun aReusedPidWithAnotherStartTimeIsLeftAlone() {
        leaveOrphan(pid = 4242)
        table.processes[4242] = FakeProcess(DesktopProcessIdentity(4242, "2026-10-11T09:30:00Z", node))

        val report = registry(nextLaunch).reapOrphans()

        assertEquals(emptyList(), report.killed)
        assertEquals(false, table.processes.getValue(4242).killed)
    }

    @Test
    fun aDifferentCommandLineIsLeftAlone() {
        leaveOrphan(pid = 4242)
        table.processes[4242] = FakeProcess(DesktopProcessIdentity(4242, "2026-10-11T09:00:05Z", "C:\\other\\node.exe"))

        assertEquals(emptyList(), registry(nextLaunch).reapOrphans().killed)
        assertEquals(false, table.processes.getValue(4242).killed)
    }

    @Test
    fun aCorruptRegistryKillsNothing() {
        leaveOrphan(pid = 4242)
        file.writeText("{not json")

        val report = registry(nextLaunch).reapOrphans()

        assertTrue(report.corrupt)
        assertEquals(emptyList(), report.killed)
        assertEquals(false, table.processes.getValue(4242).killed)
    }

    @Test
    fun aChildWhoseDesktopStillRunsIsNotAnOrphan() {
        leaveOrphan(pid = 4242)
        table.processes[owner.pid] = FakeProcess(owner)

        assertEquals(emptyList(), registry(nextLaunch).reapOrphans().killed)
        assertEquals(false, table.processes.getValue(4242).killed)
        // Its entry is kept so it can still be reaped if that desktop dies later.
        table.processes.remove(owner.pid)
        assertEquals(listOf(4242L), registry(nextLaunch).reapOrphans().killed)
    }

    @Test
    fun aChildStoppedCleanlyIsForgotten() {
        leaveOrphan(pid = 4242)
        registry().onEnded(4242)

        assertEquals(emptyList(), registry(nextLaunch).reapOrphans().killed)
    }

    @Test
    fun reapingTwiceKillsOnce() {
        leaveOrphan(pid = 4242)

        assertEquals(listOf(4242L), registry(nextLaunch).reapOrphans().killed)
        assertEquals(emptyList(), registry(nextLaunch).reapOrphans().killed)
    }

    private class FakeTable : DesktopProcessTable {
        val processes = mutableMapOf<Long, FakeProcess>()
        override fun find(pid: Long): DesktopLiveProcess? = processes[pid]?.takeIf { !it.killed }
    }

    private class FakeProcess(override val identity: DesktopProcessIdentity) : DesktopLiveProcess {
        var killed = false
        override fun killTree(): Boolean {
            killed = true
            return true
        }
    }
}
