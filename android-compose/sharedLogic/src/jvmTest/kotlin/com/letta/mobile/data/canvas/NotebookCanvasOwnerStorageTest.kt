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

/**
 * letta-mobile-bglj6.7: a document's geometry owner and compose provenance, and a compose
 * element's `_compose`, are written to the notebook (NotebookBoardStorage's split board layout)
 * and read back after a restart, so a composed note does not quietly turn into an ordinary one.
 */
class NotebookCanvasOwnerStorageTest {
    private val provenance = CanvasComposeProvenance("weekend-plan", "meals", "NOTE", "letta.canvas.compose", 1)
    private val frame = CanvasDocumentFrame(424f, 152f, 320f, 318f)

    @Test
    fun ownerAndProvenanceSurviveTheNotebookAndARestart() = runBlocking {
        val path = Files.createTempDirectory("canvas-owner-storage-")
        val canvasId = CanvasId("owner-storage")
        val heading = """{"type":"Text","text":"Weekend plan","textTopLeft":"80.0,80.0","fontSize":36.0,""" +
            """"_compose":{"artifactId":"weekend-plan","key":"heading","kind":"TEXT","catalog":"letta.canvas.compose","version":1}}"""
        NotebookLocalStore(path, "owner-peer").use { notebooks ->
            val session = CanvasSession.create(NotebookCanvasDocumentStore(notebooks), CanvasCreateOptions(canvasId = canvasId))
            session.setDocument("cmp-weekend-plan-meals", """{"version":2,"blocks":[]}""", frame = frame,
                owner = CanvasGeometryOwner.AUTO, compose = provenance)
            session.setDocument("mine", """{"version":2,"blocks":[]}""", frame = frame.copy(x = 900f))
            session.moveDocument("mine", frame.copy(x = 950f))
            session.applyLocal(CanvasOp.AddElementOp("add-h", "local_user", 10, "cmp-weekend-plan-heading", heading))
        }
        NotebookLocalStore(path, "owner-peer").use { notebooks ->
            val stored = assertNotNull(NotebookCanvasDocumentStore(notebooks).get(canvasId))
            val documents = CanvasOpProjector.documentsOf(stored.sceneJson).associateBy { it.id }
            val composed = documents.getValue("cmp-weekend-plan-meals")
            assertEquals(CanvasGeometryOwner.AUTO, composed.owner)
            assertEquals(provenance, composed.compose)
            assertEquals(frame, composed.frame)
            assertEquals(CanvasGeometryOwner.USER, documents.getValue("mine").owner)

            val element = Json.parseToJsonElement(stored.sceneJson).jsonObject.getValue("elements").jsonArray
                .single { it.jsonObject.getValue("id").jsonPrimitive.content == "cmp-weekend-plan-heading" }.jsonObject
            assertEquals("heading", element.getValue("_compose").jsonObject.getValue("key").jsonPrimitive.content)
        }
    }
}
