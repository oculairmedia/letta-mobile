package com.letta.mobile.cli.probe

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

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
}
