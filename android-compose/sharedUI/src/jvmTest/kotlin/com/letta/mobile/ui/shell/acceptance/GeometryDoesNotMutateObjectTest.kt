package com.letta.mobile.ui.shell.acceptance

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.movePluginElement
import com.letta.mobile.data.canvas.movePluginElements
import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import com.letta.mobile.data.model.CanvasElementId
import com.letta.mobile.data.model.CanvasNoteId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GeometryDoesNotMutateObjectTest {
    @Test
    fun movingAndResizingDocumentsAndPluginsOnlyChangesGeometryRegisters() = runBlocking {
        val fixture = AcceptanceWorkspace()
        val session = assertNotNull(fixture.boards.acquire(fixture.id))
        val initial = CanvasDocumentFrame(20f, 30f, 320f, 240f)
        val note = CanvasNoteId("note")
        session.setDocument(note, """{"type":"doc","content":[]}""", frame = initial, title = "Keep title", color = "#112233")
        session.applyLocalStamped(listOf(CanvasOp.SetPluginElementOp(
            opId = "place", actorId = "local_user", lamport = 0, elementId = "plugin", elementType = "ext:letta.example/widget",
            v = 1, frame = initial, ref = "job:7", props = JsonObject(mapOf("status" to JsonPrimitive("running"))),
            fallback = CanvasPluginFallback("Widget", "Keep state", "box", "https://example.test/widget"),
            meta = JsonObject(mapOf("pluginId" to JsonPrimitive("letta.example"))),
        )))
        val document = session.documents().single()
        val plugin = session.pluginElements().single()
        val moved = initial.copy(x = 120f, y = 160f)
        val resized = moved.copy(width = 480f, height = 360f)
        listOf(moved, resized).forEach { frame ->
            assertNotNull(session.moveDocument(note, frame))
            assertNotNull(session.movePluginElement(CanvasElementId("plugin"), frame))
            assertEquals(document.copy(frame = frame, owner = CanvasGeometryOwner.USER), session.documents().single())
            assertEquals(plugin.copy(frame = frame, owner = CanvasGeometryOwner.USER), session.pluginElements().single())
        }
        assertNotNull(session.movePluginElements(mapOf(CanvasElementId("plugin") to initial)))
        assertEquals(plugin.copy(owner = CanvasGeometryOwner.USER), session.pluginElements().single())
        fixture.boards.release(session)
    }
}
