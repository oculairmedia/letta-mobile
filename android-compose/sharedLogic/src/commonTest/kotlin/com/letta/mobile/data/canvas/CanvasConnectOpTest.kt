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
import kotlin.test.assertFalse
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
        assertTrue(host.logged().any { op -> op is CanvasOp.BatchOp && op.ops.any { it is CanvasOp.SetArrowBindingOp } })
    }

    @Test
    fun theArrowAndItsBindingAreOneLogEntry() = runTest {
        val host = PluginToolHost.Iroh()
        host.applyOps(note(Spot(Id("n-a"), At(16), At(24))), note(Spot(Id("n-b"), At(240), At(24)))).let { assertIs<ExternalToolResult.Success>(it) }
        val before = host.logged().size
        val result = host.applyOps(connectOp(Join(Id("a-sort"), Id("n-a"), Id("n-b"), null)))
        assertIs<ExternalToolResult.Success>(result, (result as? ExternalToolResult.Error)?.error)
        val entry = assertIs<CanvasOp.BatchOp>(host.logged().drop(before).single(), "one connect is one log entry")
        assertIs<CanvasOp.AddElementOp>(entry.ops[0])
        assertIs<CanvasOp.SetArrowBindingOp>(entry.ops[1])
        assertEquals(2, entry.ops.size)
    }

    @Test
    fun opsAfterAConnectKeepTheIndexTheAgentGaveThem() = runTest {
        val host = PluginToolHost.Iroh()
        val result = host.applyOps(
            note(Spot(Id("n-a"), At(16), At(24))),
            note(Spot(Id("n-b"), At(240), At(24))),
            connectOp(Join(Id("a-sort"), Id("n-a"), Id("n-b"), null)),
            UPDATE_MISSING,
        )
        val error = assertIs<ExternalToolResult.Error>(result).error
        assertTrue("op 3 (update_element 'missing')" in error, error)
        assertFalse("op 4" in error, error)
    }

    @Test
    fun connectMissingEndpointRefusesBatch() = runTest {
        val host = PluginToolHost.Iroh()
        val revision = host.scene().revision
        val result = host.applyOps(note(Spot(Id("n-a"), At(16), At(24))), connectOp(Join(Id("a-miss"), Id("n-a"), Id("n-gone"), null)))
        val error = assertIs<ExternalToolResult.Error>(result).error
        assertTrue("n-gone" in error, error)
        assertTrue("op 1" in error, error)
        val after = host.scene()
        assertEquals(revision, after.revision, "a refused batch must not move the revision")
        assertTrue(after.sceneJson.contains("n-a").not(), "a refused batch must publish nothing")
    }

    @Test
    fun everyRefusalNamesTheOpTheFieldAndTheReason() = runTest {
        REFUSALS.forEach { case ->
            val host = PluginToolHost.Iroh()
            val revision = host.scene().revision
            val result = host.applyOps(*case.ops.toTypedArray())
            val error = assertIs<ExternalToolResult.Error>(result, "${case.name}: expected a refusal").error
            assertTrue(case.wants.all { it in error }, "${case.name}: $error")
            assertTrue(CanvasConnect.EXAMPLE in error, "${case.name}: a working example")
            assertEquals(revision, host.scene().revision, "${case.name}: nothing published")
        }
    }

    @Test
    fun stackedNotesJoinBottomToTop() = runTest {
        val host = PluginToolHost.Iroh()
        val result = host.applyOps(note(Spot(Id("n-a"), At(16), At(24))), note(Spot(Id("n-b"), At(40), At(300))), connectOp(Join(Id("a-down"), Id("n-a"), Id("n-b"), null)))
        assertIs<ExternalToolResult.Success>(result, (result as? ExternalToolResult.Error)?.error)
        val scene = Scene(host.scene().sceneJson)
        assertEndsOnSides(scene, Id("a-down"), End(Id("n-a"), Side("bottom")), End(Id("n-b"), Side("top")))
    }

    @Test
    fun styleMapsOntoTheArrowAndAcceptsBothColourForms() = runTest {
        val host = PluginToolHost.Iroh()
        val styled = """{"type":"connect","id":"a-s","from":"n-a","to":"n-b","label":null,"style":{"strokeColor":"#1f2937","strokeWidth":3,"dashed":true}}"""
        val result = host.applyOps(note(Spot(Id("n-a"), At(16), At(24))), note(Spot(Id("n-b"), At(240), At(24))), styled)
        assertIs<ExternalToolResult.Success>(result, (result as? ExternalToolResult.Error)?.error)
        val arrow = elements(Scene(host.scene().sceneJson)).single { it.text(Id("id")) == "a-s" }
        assertEquals("#1f2937ff", arrow.text(Id("strokeColor")))
        assertEquals("DASHED", arrow.text(Id("strokeStyle")))
        assertEquals(3f, arrow.text(Id("strokeWidth"))?.toFloat())
        assertEquals(null, arrow.text(Id("text")), "an explicit null label is no label")
    }

    @Test
    fun aConnectInsideABatchOpExpandsInPlace() = runTest {
        val host = PluginToolHost.Iroh()
        val inner = connectOp(Join(Id("a-in"), Id("n-a"), Id("n-b"), null))
        val batch = """{"type":"batch","ops":[${note(Spot(Id("n-b"), At(240), At(24)))},$inner]}"""
        val result = host.applyOps(note(Spot(Id("n-a"), At(16), At(24))), batch)
        assertIs<ExternalToolResult.Success>(result, (result as? ExternalToolResult.Error)?.error)
        assertEquals(CanvasEndBinding("n-b", "left"), CanvasOpProjector.arrowBindingsOf(host.scene().sceneJson).getValue("a-in").end)

        val refused = PluginToolHost.Iroh().applyOps(
            note(Spot(Id("n-a"), At(16), At(24))),
            """{"type":"batch","ops":[${connectOp(Join(Id("a-in"), Id("n-a"), Id("n-gone"), null))}]}""",
        )
        val error = assertIs<ExternalToolResult.Error>(refused).error
        assertTrue("op 1.0 field to" in error, error)
    }

    @Test
    fun aBatchWithoutConnectIsReadAsItCame() {
        val ops = json.parseToJsonElement("[${note(Spot(Id("n-a"), At(16), At(24)))},$UPDATE_MISSING]")
        val read = assertIs<CanvasOpsRead.Ready>(CanvasBatchSteps.prepare("", ops))
        assertEquals(HostCanvasToolInputs.ops(ops), read.ops)
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
            val (x, y) = (primitive as JsonPrimitive).content.split(",")
            x.toFloat() to y.toFloat()
        }

    private fun JsonObject.text(key: Id): String? = (this[key.raw] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val BINDING_OP =
            """[{"type":"set_arrow_binding","elementId":"arrow-1","binding":{"start":{"documentId":"note-a","side":"right"},"end":{"documentId":"note-b","side":"left"}}}]"""

        const val UPDATE_MISSING =
            """{"type":"update_element","elementId":"missing","elementJson":{"type":"Shape","shapeType":"RECTANGLE","points":["0.0,0.0","10.0,10.0"],"strokeColor":"#1f2937ff","strokeWidth":2.0}}"""

        fun note(spot: Spot): String =
            """{"type":"set_document","documentId":"${spot.id.raw}","documentJson":{"version":2,"blocks":[{"id":"b","type":{"typeId":"paragraph"},"content":{"kind":"text","version":1,"text":"Hello","spans":[]}}]},"frame":{"x":${spot.x.raw},"y":${spot.y.raw},"width":140,"height":80}}"""

        fun shape(spot: Spot, type: String = "RECTANGLE"): String =
            """{"type":"add_element","elementId":"${spot.id.raw}","elementJson":{"type":"Shape","shapeType":"$type","points":["${spot.x.raw}.0,${spot.y.raw}.0","${spot.x.raw + 140}.0,${spot.y.raw + 70}.0"],"strokeColor":"#1f2937ff","strokeWidth":2.0}}"""

        fun connectOp(link: Join, extra: String = ""): String {
            val text = link.label?.let { ""","label":"${it.raw}"""" }.orEmpty()
            return """{"type":"connect","id":"${link.id.raw}","from":"${link.from.raw}","to":"${link.to.raw}"$text$extra}"""
        }

        private val A = note(Spot(Id("n-a"), At(16), At(24)))
        private val B = note(Spot(Id("n-b"), At(240), At(24)))
        private val AB = Join(Id("a-1"), Id("n-a"), Id("n-b"), null)

        val REFUSALS = listOf(
            Refusal("overlap", listOf(A, note(Spot(Id("n-b"), At(100), At(40))), connectOp(AB)), listOf("op 2 field from", "overlap", "n-a", "n-b")),
            Refusal("touching", listOf(A, note(Spot(Id("n-b"), At(156), At(24))), connectOp(AB)), listOf("op 2 field from", "overlap or touch")),
            Refusal("from equals to", listOf(A, connectOp(Join(Id("a-1"), Id("n-a"), Id("n-a"), null))), listOf("op 1 field from", "two different endpoints")),
            Refusal("unknown style key", listOf(A, B, connectOp(AB, ""","style":{"colour":"#000000"}""")), listOf("op 2 field style.colour", "unknown style key 'colour'")),
            Refusal("bad colour", listOf(A, B, connectOp(AB, ""","style":{"strokeColor":"black"}""")), listOf("op 2 field style.strokeColor", "#rrggbb or #rrggbbaa")),
            Refusal("id already on the board", listOf(A, B, connectOp(Join(Id("n-a"), Id("n-a"), Id("n-b"), null))), listOf("op 2 field id", "already on the board")),
            Refusal("line endpoint", listOf(A, shape(Spot(Id("line-1"), At(240), At(24)), "LINE"), connectOp(Join(Id("a-1"), Id("n-a"), Id("line-1"), null))), listOf("op 2 field to", "not lines or arrows")),
            Refusal("arrow endpoint", listOf(A, shape(Spot(Id("arr-1"), At(240), At(24)), "ARROW"), connectOp(Join(Id("a-1"), Id("arr-1"), Id("n-a"), null))), listOf("op 2 field from", "not lines or arrows")),
            Refusal("label not a string", listOf(A, B, connectOp(AB, ""","label":7""")), listOf("op 2 field label", "expected a string")),
            Refusal(
                "connect after remove_document",
                listOf(A, B, """{"type":"remove_document","documentId":"n-b"}""", connectOp(AB)),
                listOf("op 3 field to", "'n-b' is not a note or a shape"),
            ),
            Refusal(
                "frameless note",
                listOf(A, """{"type":"set_document","documentId":"n-b","documentJson":{"version":2,"blocks":[]}}""", connectOp(AB)),
                listOf("op 2 field to", "note 'n-b' has no frame yet", "set_document {frame}"),
            ),
        )
    }
}

private data class Refusal(val name: String, val ops: List<String>, val wants: List<String>)

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
