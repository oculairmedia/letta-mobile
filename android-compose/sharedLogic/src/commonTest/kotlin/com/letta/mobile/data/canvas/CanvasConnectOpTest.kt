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
        val result = host.applyOps(note(Spot(Id("n-a"), At(16), At(24))), note(Spot(Id("n-b"), At(240), At(24))), connectOp(Join(Id("a-sort"), Id("n-a"), Id("n-b"), Id("sort"))))
        assertIs<ExternalToolResult.Success>(result, (result as? ExternalToolResult.Error)?.error)
        val scene = host.scene().sceneJson
        val binding = CanvasOpProjector.arrowBindingsOf(scene).getValue("a-sort")
        assertEquals(CanvasEndBinding("n-a", "right"), binding.start)
        assertEquals(CanvasEndBinding("n-b", "left"), binding.end)
        assertEndsOnSides(Scene(scene), Id("a-sort"), End(Id("n-a"), Side("right")), End(Id("n-b"), Side("left")))
        assertTrue(host.logged().orEmpty().any { it is CanvasOp.SetArrowBindingOp })
    }

    @Test
    fun connectMissingEndpointRefusesBatch() = runTest {
        val host = PluginToolHost.Iroh()
        val result = host.applyOps(note(Spot(Id("n-a"), At(16), At(24))), connectOp(Join(Id("a-miss"), Id("n-a"), Id("n-gone"), null)))
        val error = assertIs<ExternalToolResult.Error>(result).error
        assertTrue("n-gone" in error, error)
        assertTrue("op 1" in error, error)
        assertTrue(host.scene().sceneJson.contains("n-a").not(), "a refused batch must publish nothing")
    }

    @Test
    fun connectNoteToShape() = runTest {
        val host = PluginToolHost.Iroh()
        val result = host.applyOps(
            note(Spot(Id("n-a"), At(16), At(40))),
            shape(Spot(Id("box"), At(240), At(40))),
            connectOp(Join(Id("a-box"), Id("n-a"), Id("box"), null)),
        )
        assertIs<ExternalToolResult.Success>(result, (result as? ExternalToolResult.Error)?.error)
        val scene = host.scene().sceneJson
        val binding = CanvasOpProjector.arrowBindingsOf(scene).getValue("a-box")
        assertEquals(CanvasEndBinding("n-a", "right"), binding.start)
        assertEquals(null, binding.end)
        val arrow = elements(Scene(scene)).single { it.text(Id("id")) == "a-box" }
        assertEquals("box", arrow.text(Id("endBinding")))
        assertEquals(null, arrow.text(Id("startBinding")))
    }

    private fun assertEndsOnSides(scene: Scene, arrowId: Id, from: End, to: End) {
        val points = elements(scene).single { it.text(Id("id")) == arrowId.raw }.points()
        assertNear(sidePoint(scene, from), points[0], from.id)
        assertNear(sidePoint(scene, to), points[1], to.id)
    }

    private fun assertNear(want: Pair<Float, Float>, got: Pair<Float, Float>, what: Id) {
        assertTrue(abs(want.first - got.first) <= 2f && abs(want.second - got.second) <= 2f, "${what.raw} want $want got $got")
    }

    private fun sidePoint(scene: Scene, end: End): Pair<Float, Float> {
        val frame = CanvasOpProjector.documentsOf(scene.raw).single { it.id == end.id.raw }.frame!!
        return CanvasSnap.anchorOn(frame, end.side.raw)
    }

    private fun elements(scene: Scene): List<JsonObject> =
        (json.parseToJsonElement(scene.raw).jsonObject["elements"] as JsonArray).map { it.jsonObject }

    private fun JsonObject.points(): List<Pair<Float, Float>> =
        (this["points"] as JsonArray).map { primitive ->
            val (x, y) = primitive.jsonPrimitiveContent().split(",")
            x.toFloat() to y.toFloat()
        }

    private fun JsonObject.text(key: Id): String? = (this[key.raw] as? JsonPrimitive)?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveContent(): String = (this as JsonPrimitive).content

    private fun note(spot: Spot): String =
        """{"type":"set_document","documentId":"${spot.id.raw}","documentJson":{"version":2,"blocks":[{"id":"b","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"Hello","spans":[]}}]},"frame":{"x":${spot.x.raw},"y":${spot.y.raw},"width":140,"height":80}}"""

    private fun shape(spot: Spot): String =
        """{"type":"add_element","elementId":"${spot.id.raw}","elementJson":{"type":"Shape","shapeType":"RECTANGLE","points":["${spot.x.raw}.0,${spot.y.raw}.0","${spot.x.raw + 140}.0,${spot.y.raw + 70}.0"],"strokeColor":"#1f2937ff","strokeWidth":2.0}}"""

    private fun connectOp(link: Join): String {
        val text = link.label?.let { ""","label":"${it.raw}"""" }.orEmpty()
        return """{"type":"connect","id":"${link.id.raw}","from":"${link.from.raw}","to":"${link.to.raw}"$text}"""
    }

    private companion object {
        const val BINDING_OP =
            """[{"type":"set_arrow_binding","elementId":"arrow-1","binding":{"start":{"documentId":"note-a","side":"right"},"end":{"documentId":"note-b","side":"left"}}}]"""
    }
}

@kotlin.jvm.JvmInline
private value class Id(val raw: String)

@kotlin.jvm.JvmInline
private value class At(val raw: Int)

@kotlin.jvm.JvmInline
private value class Side(val raw: String)

@kotlin.jvm.JvmInline
private value class Scene(val raw: String)

private data class Spot(val id: Id, val x: At, val y: At)

private data class End(val id: Id, val side: Side)

private data class Join(val id: Id, val from: Id, val to: Id, val label: Id?)
