package com.letta.mobile.data.canvas

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileCanvasOpLogTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("canvas_oplog_test_")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun processRestartSimulationRestoresPersistedCanvasOps() = runTest {
        val canvasId = CanvasId("canvas-desktop-proc-restart")
        val op1 = CanvasOp.SetBackgroundOp(
            opId = "desk-op-1",
            actorId = "user-desk",
            lamport = 1L,
            colorHex = "#123456",
        )
        val op2 = CanvasOp.AddElementOp(
            opId = "desk-op-2",
            actorId = "agent-desk",
            lamport = 2L,
            elementId = "desk-el-1",
            elementJson = """{"id":"desk-el-1","type":"circle"}""",
        )
        val op3 = CanvasOp.UpdateElementOp(
            opId = "desk-op-3",
            actorId = "user-desk",
            lamport = 5L,
            elementId = "desk-el-1",
            elementJson = """{"id":"desk-el-1","type":"circle","radius":40}""",
        )

        // Step 1: Write using first op log instance
        val log1 = FileCanvasOpLog(rootDirectory = tempDir)
        log1.append(canvasId, op1)
        log1.append(canvasId, op2)
        log1.append(canvasId, op3)

        assertEquals(3, log1.getOps(canvasId, 0L).size)

        // Step 2: "Process kill" — abandon log1 instance completely and instantiate a fresh log instance
        val log2 = FileCanvasOpLog(rootDirectory = tempDir)

        // Step 3: Verify fresh log2 recovers all ops from disk in order
        val allRecovered = log2.getOps(canvasId, 0L)
        assertEquals(3, allRecovered.size)
        assertEquals(listOf(op1, op2, op3), allRecovered)

        // Filter by sinceLamport
        val since2 = log2.getOps(canvasId, sinceLamport = 2L)
        assertEquals(listOf(op3), since2)

        // Verify has checks
        assertTrue(log2.has(canvasId, "desk-op-1"))
        assertTrue(log2.has(canvasId, "desk-op-2"))
        assertTrue(log2.has(canvasId, "desk-op-3"))
        assertFalse(log2.has(canvasId, "non-existent-op"))

        // Step 4: Idempotent append on log2
        log2.append(canvasId, op1)
        assertEquals(3, log2.getOps(canvasId, 0L).size)
    }
}
