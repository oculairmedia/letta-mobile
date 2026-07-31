package com.letta.mobile.cli.probe

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NoHttpSocketScanTest {
    // 8291 = 0x2063; 9999 = 0x270F
    private val tcpLines = listOf(
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode",
        "   0: 0100007F:A3F2 0100007F:2063 01 00000000:00000000 00:00000000 00000000  1000        0 111111 1 0000000000000000 20 4 30 10 -1",
        "   1: 0100007F:B411 0100007F:2063 01 00000000:00000000 00:00000000 00000000  1000        0 222222 1 0000000000000000 20 4 30 10 -1",
        "   2: 0100007F:C522 0100007F:270F 01 00000000:00000000 00:00000000 00000000  1000        0 333333 1 0000000000000000 20 4 30 10 -1",
    )

    @Test
    fun `counts only own-process sockets to the target port`() {
        // Only inode 111111 belongs to this process; 222222 is another process's
        // connection to :8291 and must NOT count.
        assertEquals(1, NoHttpSocketScan.countMatches(setOf(111_111L, 333_333L), tcpLines, 8291))
    }

    @Test
    fun `zero when no fd inode matches`() {
        assertEquals(0, NoHttpSocketScan.countMatches(setOf(999_999L), tcpLines, 8291))
    }

    @Test
    fun `zero when remote port differs`() {
        assertEquals(0, NoHttpSocketScan.countMatches(setOf(111_111L, 222_222L), tcpLines, 4501))
    }

    @Test
    fun `header and malformed lines are ignored`() {
        val malformed = listOf("garbage", "", "   9: nonsense")
        assertEquals(0, NoHttpSocketScan.countMatches(setOf(1L), malformed + tcpLines.first(), 8291))
    }

    @Test
    fun `socket inode parsing handles fd link targets`() {
        assertEquals(41_234L, NoHttpSocketScan.parseSocketInode("socket:[41234]"))
        assertNull(NoHttpSocketScan.parseSocketInode("/dev/null"))
        assertNull(NoHttpSocketScan.parseSocketInode("pipe:[123]"))
        assertNull(NoHttpSocketScan.parseSocketInode(null))
    }

    @Test
    fun `unsupported platform returns null`() {
        assertNull(NoHttpSocketScan.connectionsToPort(8291, procRoot = "/definitely/not/proc"))
    }

    /**
     * lgns8.21.9 regression: the scan must follow the requested PID. Before the
     * fix both the fd dir and the net/tcp lookup hardcoded `self`, so a wrapper
     * process dialing :8291 counted as ZERO while the clean probe process passed
     * the gate. Reverting the pid parameter makes this fail (1 -> 0).
     */
    @Test
    fun `scans the requested pid not self`(@TempDir root: File) {
        // Wrapper PID 4242 holds a socket to :8291; the probe process (self) is clean.
        FakeProcRoot.write(root, pid = "4242", socketInodes = listOf(111_111L), tcpLines = tcpLines)
        FakeProcRoot.write(root, pid = "self", socketInodes = listOf(333_333L), tcpLines = tcpLines)

        assertEquals(1, NoHttpSocketScan.connectionsToPort(8291, pid = "4242", procRoot = root.path))
        assertEquals(0, NoHttpSocketScan.connectionsToPort(8291, pid = "self", procRoot = root.path))
    }

    @Test
    fun `missing pid directory degrades to null instead of a false green`(@TempDir root: File) {
        FakeProcRoot.write(root, pid = "4242", socketInodes = listOf(111_111L), tcpLines = tcpLines)
        assertNull(NoHttpSocketScan.connectionsToPort(8291, pid = "9999", procRoot = root.path))
    }

    /**
     * lgns8.21.9 acceptance: a separate wrapper-like child process dials the
     * port while this probe process stays clean — the wrapper-scoped scan sees
     * the connection, the self-scoped scan does not.
     */
    @Test
    fun `attributes a real child process connection to that child`() {
        assumeTrue(File("/proc/self/fd").isDirectory, "procfs required")
        assumeTrue(File("/bin/bash").canExecute(), "bash required")
        java.net.ServerSocket(0).use { server ->
            val port = server.localPort
            val pidFile = File.createTempFile("wrapper-pid", ".txt")
            val child = ProcessBuilder(
                "/bin/bash", "-c", "echo \$\$ > ${pidFile.path}; exec 3<>/dev/tcp/127.0.0.1/$port; sleep 30",
            ).start()
            try {
                server.accept().use {
                    // bash publishes fd 3 right after connect(); wait deterministically
                    // for the pid file and the fd to appear rather than sampling once.
                    val deadline = System.nanoTime() + 10_000_000_000L
                    var childCount = 0
                    while (System.nanoTime() < deadline && childCount == 0) {
                        val childPid = pidFile.readText().trim()
                        childCount = if (childPid.isEmpty()) {
                            0
                        } else {
                            NoHttpSocketScan.connectionsToPort(port, childPid) ?: 0
                        }
                        if (childCount == 0) Thread.sleep(25)
                    }
                    assertTrue(childCount >= 1, "wrapper-scoped scan must see the child's connection")
                    assertEquals(
                        0,
                        NoHttpSocketScan.connectionsToPort(port, NoHttpSocketScan.SELF),
                        "the probe process itself never dialed the port",
                    )
                }
            } finally {
                child.destroyForcibly().waitFor()
                pidFile.delete()
            }
        }
    }
}

/** Builds a fake procfs tree: socket-link fds under `<root>/<pid>/fd`, plus `<root>/<pid>/net/tcp`. */
internal object FakeProcRoot {
    fun write(root: File, pid: String, socketInodes: List<Long>, tcpLines: List<String>) {
        val fdDir = File(root, "$pid/fd").apply { mkdirs() }
        socketInodes.forEachIndexed { index, inode ->
            java.nio.file.Files.createSymbolicLink(
                File(fdDir, index.toString()).toPath(),
                File("socket:[$inode]").toPath(),
            )
        }
        File(root, "$pid/net").mkdirs()
        File(root, "$pid/net/tcp").writeText(tcpLines.joinToString("\n"))
    }
}
