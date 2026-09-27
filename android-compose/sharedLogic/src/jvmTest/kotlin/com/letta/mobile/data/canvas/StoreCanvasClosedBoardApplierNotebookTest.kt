package com.letta.mobile.data.canvas

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StoreCanvasClosedBoardApplierNotebookTest {
    @Test
    fun remoteOpWhileBoardClosedSurvivesNotebookRestartAndKeepsIndependentElement() = runBlocking {
        val path = Files.createTempDirectory("canvas-closed-notebook-")
        val canvasId = CanvasId.forConversation("closed-conversation")
        val opLog = InMemoryCanvasOpLog()
        val op = CanvasOp.AddElementOp(
            "remote-add", "agent-1", 1L, "remote-text",
            """{"id":"remote-text","type":"Text","text":"drawn while closed"}""",
        )

        NotebookLocalStore(path, "canvas-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            CanvasSession.getOrCreateForConversation(
                store, "closed-conversation", CanvasConversationOptions(agentId = "agent-1", opLog = opLog),
            ) // No open board session receives the remote op.
            val notebookId = notebooks.listDocuments().single()
            notebooks.putBoardElement(
                notebookId, Json.parseToJsonElement("""{"id":"independent","text":"kept"}""").jsonObject,
            )

            val closedBoard = StoreCanvasClosedBoardApplier(store, opLog)
            assertTrue(closedBoard.apply(canvasId, op, vouchedActor = null))
            assertEquals(1, opLog.getOps(canvasId).count { it.opId == op.opId })
        }

        NotebookLocalStore(path, "canvas-peer").use { notebooks ->
            val store = NotebookCanvasDocumentStore(notebooks)
            val reopened = assertNotNull(CanvasSession.open(store, canvasId, CanvasConversationOptions(opLog = opLog)))
            val elements = Json.parseToJsonElement(reopened.sceneJsonOrEmpty()).jsonObject
                .getValue("elements").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(setOf("remote-text", "independent"), elements.keys)
            assertEquals("drawn while closed", elements.getValue("remote-text").jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals("kept", elements.getValue("independent").jsonObject.getValue("text").jsonPrimitive.content)
        }
    }
}
