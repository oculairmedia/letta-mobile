package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanvasOpLogTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun opLog_appendGetAndDeduplicate() = runTest {
        val opLog = InMemoryCanvasOpLog()
        val canvasId = CanvasId("test-canvas-1")

        val op1 = CanvasOp.AddElementOp(
            opId = "op-1",
            actorId = "user-1",
            lamport = 1L,
            elementId = "elem-1",
            elementJson = """{"id": "elem-1", "type": "Text", "text": "Hello"}""",
        )
        val op2 = CanvasOp.AddElementOp(
            opId = "op-2",
            actorId = "user-1",
            lamport = 2L,
            elementId = "elem-2",
            elementJson = """{"id": "elem-2", "type": "Shape", "shapeType": "RECTANGLE"}""",
        )

        opLog.append(canvasId, op1)
        opLog.append(canvasId, op2)

        // Duplicate append is ignored
        opLog.append(canvasId, op1)

        assertTrue(opLog.has(canvasId, "op-1"))
        assertTrue(opLog.has(canvasId, "op-2"))
        assertFalse(opLog.has(canvasId, "op-3"))

        val allOps = opLog.getOps(canvasId, sinceLamport = 0L)
        assertEquals(2, allOps.size)
        assertEquals("op-1", allOps[0].opId)
        assertEquals("op-2", allOps[1].opId)

        val sinceOps = opLog.getOps(canvasId, sinceLamport = 1L)
        assertEquals(1, sinceOps.size)
        assertEquals("op-2", sinceOps[0].opId)
    }

    @Test
    fun opLog_observeBroadcastsAppendedOps() = runTest {
        val opLog = InMemoryCanvasOpLog()
        val canvasId = CanvasId("test-canvas-2")

        val op = CanvasOp.SetBackgroundOp(
            opId = "op-bg",
            actorId = "user-1",
            lamport = 1L,
            colorHex = "#ff0000ff",
        )

        val flow = opLog.observe(canvasId)
        var receivedOp: CanvasOp? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            receivedOp = flow.first()
        }
        runCurrent()

        opLog.append(canvasId, op)
        runCurrent()

        assertEquals("op-bg", receivedOp?.opId)
        assertEquals("#ff0000ff", (receivedOp as? CanvasOp.SetBackgroundOp)?.colorHex)
    }

    @Test
    fun opProjector_replaysOpsFromEmptyToExpectedScene() {
        val ops = listOf(
            CanvasOp.SetBackgroundOp(
                opId = "op-1",
                actorId = "user-1",
                lamport = 1L,
                colorHex = "#eceff1ff",
            ),
            CanvasOp.AddElementOp(
                opId = "op-2",
                actorId = "user-1",
                lamport = 2L,
                elementId = "title",
                elementJson = """{"id": "title", "type": "Text", "text": "Draft Title"}""",
            ),
            CanvasOp.AddElementOp(
                opId = "op-3",
                actorId = "user-2",
                lamport = 3L,
                elementId = "box-1",
                elementJson = """{"id": "box-1", "type": "Shape", "shapeType": "RECTANGLE"}""",
            ),
            // Update title
            CanvasOp.UpdateElementOp(
                opId = "op-4",
                actorId = "user-1",
                lamport = 4L,
                elementId = "title",
                elementJson = """{"id": "title", "type": "Text", "text": "Final Title"}""",
            ),
            // Remove box-1
            CanvasOp.RemoveElementOp(
                opId = "op-5",
                actorId = "user-2",
                lamport = 5L,
                elementId = "box-1",
            ),
        )

        val projected = CanvasOpProjector.project(baseSceneJson = "", ops = ops)
        val parsed = json.parseToJsonElement(projected).jsonObject

        assertEquals("#eceff1ff", parsed["bgColor"]?.jsonPrimitive?.content)
        val elements = parsed["elements"]?.jsonArray
        assertEquals(1, elements?.size)

        val titleElem = elements?.get(0)?.jsonObject
        assertEquals("title", titleElem?.get("id")?.jsonPrimitive?.content)
        assertEquals("Final Title", titleElem?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun opProjector_replaceSceneOpAndBatchOp() {
        val initialScene = CanvasOpProjector.emptySceneJson()

        val replaceOp = CanvasOp.ReplaceSceneOp(
            opId = "rep-1",
            actorId = "agent",
            lamport = 10L,
            sceneJson = """{"bgColor": "#112233ff", "elements": [{"id": "node-1", "type": "Node"}]}""",
        )

        val batchOp = CanvasOp.BatchOp(
            opId = "batch-1",
            actorId = "user",
            lamport = 11L,
            ops = listOf(
                CanvasOp.AddElementOp(
                    opId = "add-2",
                    actorId = "user",
                    lamport = 11L,
                    elementId = "node-2",
                    elementJson = """{"id": "node-2", "type": "Node"}""",
                )
            ),
        )

        val afterReplace = CanvasOpProjector.project(initialScene, listOf(replaceOp))
        val afterBatch = CanvasOpProjector.project(afterReplace, listOf(batchOp))

        val parsed = json.parseToJsonElement(afterBatch).jsonObject
        assertEquals("#112233ff", parsed["bgColor"]?.jsonPrimitive?.content)
        val elements = parsed["elements"]?.jsonArray
        assertEquals(2, elements?.size)
        assertEquals("node-1", elements?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals("node-2", elements?.get(1)?.jsonObject?.get("id")?.jsonPrimitive?.content)
    }
}
