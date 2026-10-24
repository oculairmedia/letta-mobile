package com.letta.mobile.ui.canvas

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSceneStateGuard
import com.letta.mobile.data.model.CanvasId
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a board does with each revision from elsewhere (letta-mobile-v3qf9). On the web the board's
 * "background" work runs on the one main thread, and each revision was parsed whole half a dozen
 * times: the same scene arriving again is not parsed at all, and a real change is parsed once.
 */
class CanvasExternalSyncParseTest {
    private val rect = shape("rect", zIndex = 0, points = """"0.0,0.0","50.0,40.0"""", kind = "RECTANGLE")
    private val circle = shape("circle", zIndex = 1, points = """"100.0,100.0","160.0,180.0"""", kind = "CIRCLE")
    private val before = CanvasOpProjector.project("", listOf(CanvasOp.AddElementOp("op-1", "a", 1L, "rect", rect)))
    private val after = CanvasOpProjector.project(before, listOf(CanvasOp.AddElementOp("op-2", "b", 2L, "circle", circle)))

    private var parses = 0
    private val countingParse: (String) -> JsonObject? = { parses++; CanvasOpProjector.parseSceneOrNull(it) }

    @Test
    fun theSceneTheBoardAlreadyHasIsNotParsed() {
        val result = evaluate(after, lastSyncedScene = after)

        assertFalse(result.shouldImport)
        assertEquals(0, parses)
        assertEquals(2L, result.newImportedRev)
    }

    @Test
    fun theBoardsOwnSaveIsNotParsed() {
        val result = evaluate(after, lastExportedJson = after)

        assertFalse(result.shouldImport)
        assertEquals(0, parses)
    }

    @Test
    fun aRealChangeIsParsedOnceAndDecodedFromThatTree() {
        val result = evaluate(after)

        assertTrue(result.shouldImport)
        assertEquals(1, parses)
        assertEquals(1, result.changedElements)
        assertEquals("circle", result.changedSample)
        // The rest of an import works on the evaluation's tree; none of it takes the text to parse again.
        val scene = assertNotNull(result.scene)
        val drawable = CanvasSceneStateGuard(null).drawable(scene, after.length)
        val payload = assertNotNull(CanvasImageAssets.parse(drawable, null))
        assertEquals(setOf("rect", "circle"), payload.elements.map { it.id }.toSet())
        assertEquals(result.drawing, CanvasWorkspaceSupport.drawingOf(after))
    }

    @Test
    fun aSceneThatChangedOnlyBookkeepingIsParsedButNotImported() {
        val resent = CanvasOpProjector.project(before, listOf(CanvasOp.UpdateElementOp("op-3", "b", 7L, "rect", rect)))

        val result = evaluate(resent)

        assertFalse(result.shouldImport)
        assertEquals(1, parses)
    }

    private fun shape(id: String, zIndex: Int, points: String, kind: String): String =
        """{"id":"$id","type":"Shape","zIndex":$zIndex,"points":[$points],""" +
            """"strokeColor":"#000000ff","strokeWidth":1.0,"shapeType":"$kind"}"""

    private fun evaluate(scene: String, lastExportedJson: String? = null, lastSyncedScene: String? = null): ExternalSyncResult {
        val doc = CanvasDocument(id = CanvasId("c"), title = "c", revision = 2L, sceneJson = scene, updatedAtEpochMs = 0L)
        val params = ExternalSyncParams(
            doc = doc,
            lastImportedRev = 1L,
            lastExportedJson = lastExportedJson,
            lastDrawing = CanvasWorkspaceSupport.drawingOf(before),
            lastSyncedScene = lastSyncedScene,
        )
        return assertNotNull(CanvasWorkspaceSupport.evaluateExternalDocSync(params, countingParse))
    }
}
