package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.compose.CanvasComposeCompiler
import com.letta.mobile.data.canvas.compose.CanvasComposeGuide
import com.letta.mobile.data.canvas.compose.CanvasComposeService
import com.letta.mobile.data.canvas.compose.ComposeCompilation
import com.letta.mobile.data.canvas.compose.ComposeOutcome
import com.letta.mobile.data.canvas.compose.ComposeStatus
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * letta-mobile-bglj6.10: a composed artifact published through a CanvasSession is written to the
 * notebook (NotebookBoardStorage, one entry per document) and reads back after a restart exactly
 * as compiled: every note with its frame, owner, colour, title and provenance, every TEXT/GROUP
 * element with its `_compose`. And the stored board still knows the artifact, so a retry after
 * the restart publishes nothing.
 */
class NotebookComposeStorageTest {
    @Test
    fun aComposedArtifactSurvivesTheNotebookAndARestart() = runBlocking {
        val path = Files.createTempDirectory("canvas-compose-storage-")
        val canvasId = CanvasId.forConversation("conv-compose")
        val request = Json.parseToJsonElement(CanvasComposeGuide.EXAMPLE_REQUEST)
        val compiled = assertIs<ComposeCompilation.Ready>(CanvasComposeCompiler.compile(request, "") { error("named") })

        NotebookLocalStore(path, "compose-peer").use { notebooks ->
            val session = CanvasSession.create(NotebookCanvasDocumentStore(notebooks), CanvasCreateOptions(canvasId = canvasId))
            val outcome = CanvasComposeService.compose(request, canvasId.value, session.sceneJsonOrEmpty(), toolCallId = null) { ops ->
                var lamport = 0L
                session.applyOps(ops.map { it.withActor(CanvasSession.LOCAL_USER_ACTOR_ID).withStamp("compose-${++lamport}", lamport) }).revision
            }
            assertEquals(ComposeStatus.PUBLISHED, assertIs<ComposeOutcome.Done>(outcome).receipt.status)
        }

        NotebookLocalStore(path, "compose-peer").use { notebooks ->
            val stored = assertNotNull(NotebookCanvasDocumentStore(notebooks).get(canvasId))
            val documents = CanvasOpProjector.documentsOf(stored.sceneJson).associateBy { it.id }
            compiled.ops.filterIsInstance<CanvasOp.SetDocumentOp>().forEach { op ->
                val document = documents.getValue(op.documentId)
                assertEquals(op.documentJson, document.json, op.documentId)
                assertEquals(op.frame, document.frame, op.documentId)
                assertEquals(op.owner, document.owner, op.documentId)
                assertEquals(op.compose, document.compose, op.documentId)
                assertEquals(op.color, document.color, op.documentId)
                assertEquals(op.title, document.title, op.documentId)
            }
            val elements = Json.parseToJsonElement(stored.sceneJson).jsonObject.getValue("elements").jsonArray
                .associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
            compiled.ops.filterIsInstance<CanvasOp.AddElementOp>().forEach { op ->
                val compiledElement = Json.parseToJsonElement(op.elementJson).jsonObject
                val element = elements.getValue(op.elementId).jsonObject
                assertEquals(compiledElement.getValue("_compose"), element.getValue("_compose"), op.elementId)
                compiledElement.forEach { (key, value) -> assertEquals(value, element[key], "${op.elementId}.$key") }
            }

            val retry = assertIs<ComposeCompilation.Ready>(CanvasComposeCompiler.compile(request, stored.sceneJson) { error("named") })
            assertTrue(retry.alreadyPublished)
            assertEquals(emptyList(), retry.ops)
            assertEquals(compiled.bounds, retry.bounds)
        }
    }
}
