package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.canvas.CanvasId
import com.letta.mobile.data.canvas.CanvasOp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RoomCanvasOpLogTest {

    private lateinit var database: LettaDatabase
    private lateinit var opLog: RoomCanvasOpLog

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        opLog = RoomCanvasOpLog(database.canvasOpDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun appendAndRetrieveOpsWithSinceLamport() = runBlocking {
        val canvasId = CanvasId("canvas-ops-test-1")
        val op1 = CanvasOp.SetBackgroundOp(
            opId = "op-1",
            actorId = "user-1",
            lamport = 1L,
            colorHex = "#ff0000",
        )
        val op2 = CanvasOp.AddElementOp(
            opId = "op-2",
            actorId = "agent-1",
            lamport = 2L,
            elementId = "elem-1",
            elementJson = """{"id":"elem-1","type":"rectangle"}""",
        )
        val op3 = CanvasOp.UpdateElementOp(
            opId = "op-3",
            actorId = "user-1",
            lamport = 5L,
            elementId = "elem-1",
            elementJson = """{"id":"elem-1","type":"rectangle","color":"#00ff00"}""",
        )

        opLog.append(canvasId, op1)
        opLog.append(canvasId, op2)
        opLog.append(canvasId, op3)

        val allOps = opLog.getOps(canvasId, sinceLamport = 0L)
        assertEquals(3, allOps.size)
        assertEquals(listOf(op1, op2, op3), allOps)

        val opsSince2 = opLog.getOps(canvasId, sinceLamport = 2L)
        assertEquals(1, opsSince2.size)
        assertEquals(listOf(op3), opsSince2)

        val opsSince5 = opLog.getOps(canvasId, sinceLamport = 5L)
        assertTrue(opsSince5.isEmpty())
    }

    @Test
    fun idempotentAppendDoesNotDuplicate() = runBlocking {
        val canvasId = CanvasId("canvas-ops-test-2")
        val op = CanvasOp.RemoveElementOp(
            opId = "op-del-1",
            actorId = "user-1",
            lamport = 10L,
            elementId = "elem-to-del",
        )

        opLog.append(canvasId, op)
        opLog.append(canvasId, op)

        val ops = opLog.getOps(canvasId, sinceLamport = 0L)
        assertEquals(1, ops.size)
        assertEquals(op, ops.first())
    }

    @Test
    fun hasReturnsExpectedPresence() = runBlocking {
        val canvasId = CanvasId("canvas-ops-test-3")
        val op = CanvasOp.ReplaceSceneOp(
            opId = "op-replace-1",
            actorId = "agent-1",
            lamport = 1L,
            sceneJson = """{"elements":[]}""",
        )

        assertFalse(opLog.has(canvasId, "op-replace-1"))
        opLog.append(canvasId, op)
        assertTrue(opLog.has(canvasId, "op-replace-1"))
        assertFalse(opLog.has(canvasId, "non-existent-op"))
    }

    @Test
    fun roundTripsBatchOp() = runBlocking {
        val canvasId = CanvasId("canvas-ops-test-4")
        val batch = CanvasOp.BatchOp(
            opId = "batch-1",
            actorId = "user-1",
            lamport = 3L,
            ops = listOf(
                CanvasOp.SetBackgroundOp("sub-1", "user-1", 3L, "#ffffff"),
                CanvasOp.RemoveElementOp("sub-2", "user-1", 3L, "elem-x"),
            ),
        )

        opLog.append(canvasId, batch)
        val retrieved = opLog.getOps(canvasId, 0L)
        assertEquals(1, retrieved.size)
        assertEquals(batch, retrieved.first())
    }
}
