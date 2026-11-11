package com.letta.mobile.ui.shell.acceptance

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.PayLoad
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.translate
import io.ak1.drawbox.domain.model.withBounds
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
    fun drawingMoveAndResizeKeepEveryNonGeometryFieldExceptTheDocumentedTouchTimestamp() = runBlocking {
        val fixture = AcceptanceWorkspace()
        val session = assertNotNull(fixture.boards.acquire(fixture.id))
        val shape = Element.Shape(id = "shape", shapeType = ShapeType.RECTANGLE,
            points = listOf(Offset(10f, 20f), Offset(110f, 120f)), strokeColor = Color.Red,
            fillColor = Color.Blue, strokeWidth = 3f, zIndex = 7, rotation = 15f, cornerRadius = 4f,
            createdAt = 10L, modifiedAt = 20L, text = "Keep content", fontSize = 18f)
        session.applyLocalScene(DrawingSerializer.serialize(PayLoad(Color.White, listOf(shape))))
        val translated = shape.translate(Offset(30f, 40f)) as Element.Shape
        val resized = translated.withBounds(Rect(40f, 60f, 240f, 260f)) as Element.Shape
        listOf(translated, resized).forEach { changed ->
            session.applyLocalScene(DrawingSerializer.serialize(PayLoad(Color.White, listOf(changed))))
            val stored = DrawingSerializer.deserialize(session.document.value!!.sceneJson).elements.single() as Element.Shape
            assertEquals(changed.points, stored.points)
            val withoutGeometry = stored.copy(points = shape.points, modifiedAt = shape.modifiedAt)
            assertEquals(shape, withoutGeometry, "geometry preserves drawing content and style")
        }
        fixture.boards.release(session)
    }

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
