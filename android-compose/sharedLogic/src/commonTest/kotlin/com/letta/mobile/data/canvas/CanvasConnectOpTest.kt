package com.letta.mobile.data.canvas

import com.letta.mobile.data.controller.extras.ExternalToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * connect (letta-mobile-i9yps.1): one agent op becomes a bound arrow. Dropping the binding step
 * leaves an unbound arrow and these tests fail.
 */
class CanvasConnectOpTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun setArrowBindingDecodesAndTheToolTextListsIt() {
        val ops = HostCanvasToolInputs.ops(json.parseToJsonElement(BINDING_OP))
        val binding = assertIs<CanvasOp.SetArrowBindingOp>(ops.single())
        assertEquals("arrow-1", binding.elementId)
        assertEquals(CanvasEndBinding("note-a", "right"), binding.binding.start)
        assertEquals(CanvasEndBinding("note-b", "left"), binding.binding.end)
        val description = CanvasToolContract.applyOps.description
        assertTrue("set_arrow_binding" in description)
        assertTrue("connect " in description)
    }

    @Test
    fun connectBindsTwoNotes() = runTest {
        val host = PluginToolHost.Iroh()
        val result = host.applyOps(note("n-a", 16, 24), note("n-b", 240, 24), connect("a-sort", "n-a", "n-b", "sort"))
        assertIs<ExternalToolResult.Success>(result, (result as? ExternalToolResult.Error)?.error)
        val scene = host.scene().sceneJson
        val binding = CanvasOpProjector.arrowBindingsOf(scene).getValue("a-sort")
        assertEquals(CanvasEndBinding("n-a", "right"), binding.start)
        assertEquals(CanvasEndBinding("n-b", "left"), binding.end)
        assertEndsOnSides(scene, "a-sort", "n-a" to "right", "n-b" to "left")
        assertTrue(host.logged().orEmpty().any { it is CanvasOp.SetArrowBindingOp })
    }

    @Test
    fun connectMissingEndpointRefusesBatch() = runTest {
        val host = PluginToolHost.Iroh()
        val result = host.applyOps(note("n-a", 16, 24), connect("a-miss", "n-a", "n-gone", null))
        val error = assertIs<ExternalToolResult.Error>(result).error
        assertTrue("n-gone" in error, error)
        assertTrue("op 1" in error, error)
        assertTrue(host.scene().sceneJson.contains("n-a").not(), "a refused batch must publish nothing")
    }

    @Test
    fun connectNoteToShape() = runTest {
        val host = PluginToolHost.Iroh()
        val result = host.applyOps(note("n-a", 16, 40), shape("box", 240, 40), connect("a-box", "n-a", "box", null))
        assertIs<ExternalToolResult.Success>(result, (result as? ExternalToolResult.Error)?.error)
        val scene = host.scene().sceneJson
        val binding = CanvasOpProjector.arrowBindingsOf(scene).getValue("a-box")
        assertEquals(CanvasEndBinding("n-a", "right"), binding.start)
        assertEquals(null, binding.end)
        val arrow = elements(scene).single { it.text("id") == "a-box" }
        assertEquals("box", arrow.text("endBinding"))
        assertEquals(null, arrow.text("startBinding"))
    }

    private fun assertEndsOnSides(scene: String, arrowId: String, from: Pair<String, String>, to: Pair<String, String>) {
        val points = elements(scene).single { it.text("id") == arrowId }.points()
        assertNear(sidePoint(scene, from.first, from.second), points[0], from.first)
        assertNear(sidePoint(scene, to.first, to.second), points[1], to.second)
    }

    private fun assertNear(want: Pair<Float, Float>, got: Pair<Float, Float>, what: String) {
        assertTrue(abs(want.first - got.first) <= 2f && abs(want.second - got.second) <= 2f, "$what want $want got $got")
    }

    private fun sidePoint(scene: String, id: String, side: String): Pair<Float, Float> {
        val frame = CanvasOpProjector.documentsOf(scene).single { it.id == id }.frame!!
        return CanvasSnap.anchorOn(frame, side)
    }

    private fun elements(scene: String): List<JsonObject> =
        (json.parseToJsonElement(scene).jsonObject["elements"] as JsonArray).map { it.jsonObject }

    private fun JsonObject.points(): List<Pair<Float, Float>> =
        (this["points"] as JsonArray).map { primitive ->
            val (x, y) = primitive.jsonPrimitiveContent().split(",")
            x.toFloat() to y.toFloat()
        }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String = (this as JsonPrimitive).content

    private fun note(id: String, x: Int, y: Int): String =
        """{"type":"set_document","documentId":"$id","documentJson":{"version":2,"blocks":[{"id":"b","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"Hello","spans":[]}}]},"frame":{"x":$x,"y":$y,"width":140,"height":80}}"""

    private fun shape(id: String, x: Int, y: Int): String =
        """{"type":"add_element","elementId":"$id","elementJson":{"type":"Shape","shapeType":"RECTANGLE","points":["$x.0,$y.0","${x + 140}.0,${y + 70}.0"],"strokeColor":"#1f2937ff","strokeWidth":2.0}}"""

    private fun connect(id: String, from: String, to: String, label: String?): String {
        val text = label?.let { ""","label":"$it"""" }.orEmpty()
        return """{"type":"connect","id":"$id","from":"$from","to":"$to"$text}"""
    }

    private companion object {
        const val BINDING_OP =
            """[{"type":"set_arrow_binding","elementId":"arrow-1","binding":{"start":{"documentId":"note-a","side":"right"},"end":{"documentId":"note-b","side":"left"}}}]"""
    }
}
