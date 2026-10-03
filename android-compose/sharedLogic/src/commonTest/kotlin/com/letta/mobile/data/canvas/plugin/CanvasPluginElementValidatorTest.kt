package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasBatchCheck
import com.letta.mobile.data.canvas.CanvasBatchValidator
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasOpsCheck
import com.letta.mobile.data.canvas.CanvasSceneLimits
import com.letta.mobile.data.canvas.CanvasSceneState
import com.letta.mobile.data.canvas.CanvasSceneValidator
import com.letta.mobile.data.canvas.CanvasStateInvariant
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.ID
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.TYPE
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.fallback
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.place
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.progress
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.props
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.remove
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.snapshot
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.1: the plugin element envelope on the one validator path. Every rule has a
 * write it refuses, named by where in the write it is; a kind the host does not know is accepted
 * with a WARN, never refused, so a board relayed by a host without the plugin still converges.
 */
class CanvasPluginElementValidatorTest {
    private val board = CanvasOpProjector.emptySceneJson()

    /** The paths [op] is refused at on the validator path. */
    private fun refusedPaths(op: CanvasOp.SetPluginElementOp): List<String?> {
        val check = assertIs<CanvasOpsCheck.Invalid>(CanvasSceneValidator.ops(listOf(op)), "expected a refusal of $op")
        return check.problems.map { it.path }
    }

    private fun assertRefusedAt(path: String, op: CanvasOp.SetPluginElementOp) =
        assertTrue(path in refusedPaths(op), "expected a refusal at $path, got ${refusedPaths(op)}")

    @Test
    fun aWellFormedFirstWriteIsAccepted() {
        val check = assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(board, listOf(place(1))))
        assertEquals(TYPE, CanvasOpProjector.pluginElementsOf(check.sceneJson).single().type)
    }

    @Test
    fun everyEnvelopeRuleRefusesAtItsPath() {
        val good = place(1)
        val cases = mapOf(
            "/elementId" to good.copy(elementId = " "),
            "/elementType" to good.copy(elementType = "letta.example/widget"),
            "/v" to good.copy(v = 0),
            "/frame/x" to good.copy(frame = CanvasDocumentFrame(Float.NaN, 0f, 10f, 10f)),
            "/frame/y" to good.copy(frame = CanvasDocumentFrame(0f, Float.POSITIVE_INFINITY, 10f, 10f)),
            "/frame/width" to good.copy(frame = CanvasDocumentFrame(0f, 0f, 0f, 10f)),
            "/frame/height" to good.copy(frame = CanvasDocumentFrame(0f, 0f, 10f, -1f)),
            "/owner" to good.copy(frame = null, owner = CanvasGeometryOwner.USER),
            "/ref" to good.copy(ref = "r".repeat(CanvasPluginElementSchema.REF.max + 1)),
            "/props" to good.copy(props = props("blob" to "x".repeat(CanvasPluginElementSchema.PROPS.max))),
            "/props/nested" to good.copy(props = JsonObject(mapOf("nested" to JsonObject(emptyMap())))),
            "/meta/list" to good.copy(meta = JsonObject(mapOf("list" to JsonArray(emptyList())))),
            "/snapshot/assetRef" to good.copy(snapshot = snapshot.copy(assetRef = "https://example.test/a.png")),
            "/snapshot/mediaType" to good.copy(snapshot = snapshot.copy(mediaType = "text/html")),
            "/snapshot/width" to good.copy(snapshot = snapshot.copy(width = 0)),
            "/fallback/title" to good.copy(fallback = fallback.copy(title = "")),
            "/fallback/subtitle" to good.copy(fallback = fallback.copy(subtitle = "s".repeat(CanvasPluginElementSchema.SUBTITLE.max + 1))),
            "/fallback/icon" to good.copy(fallback = fallback.copy(icon = "i".repeat(CanvasPluginElementSchema.ICON.max + 1))),
            "/fallback/openUrl" to good.copy(fallback = fallback.copy(openUrl = "javascript:alert(1)")),
            "/" to CanvasOp.SetPluginElementOp("o", "a", 1, ID),
        )
        cases.forEach { (path, op) -> assertRefusedAt(path, op) }
        assertRefusedAt("/fallback/title", good.copy(fallback = fallback.copy(title = "t".repeat(CanvasPluginElementSchema.TITLE.max + 1))))
        assertRefusedAt("/fallback/openUrl", good.copy(fallback = fallback.copy(openUrl = "https://x.test/" + "p".repeat(CanvasPluginElementSchema.OPEN_URL.max))))
        assertRefusedAt("/v", good.copy(v = null))
        assertRefusedAt("/v", progress(2, 0.5).copy(v = 2))
    }

    @Test
    fun aRefusedBatchNamesTheElementAndThePathAndPublishesNothing() {
        val bad = place(1).copy(fallback = fallback.copy(title = " "))
        val refused = assertIs<CanvasBatchCheck.Invalid>(CanvasBatchValidator.check(board, listOf(bad)))
        val violation = refused.violations.single()
        assertEquals(CanvasStateInvariant.PLUGIN_ELEMENT_SHAPE, violation.violation.invariant)
        assertEquals("$ID/fallback/title", violation.violation.subject)
        assertTrue("set_plugin_element" in refused.message, refused.message)
    }

    @Test
    fun aKindTheHostDoesNotKnowIsAcceptedWithAWarning() {
        Telemetry.clear()
        val unknown = place(1).copy(elementType = "ext:someone.else/gadget")
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(board, listOf(unknown)))
        val warning = Telemetry.snapshot().single { it.name == "pluginElement.unknownKind" }
        assertEquals(Telemetry.Level.WARN, warning.level)

        Telemetry.clear()
        val known = PluginKindCatalog { type, _ -> if (type == TYPE) JsonObject(emptyMap()) else null }
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(board, listOf(place(1)), known))
        assertTrue(Telemetry.snapshot().none { it.name == "pluginElement.unknownKind" })
    }

    @Test
    fun aFirstWriteMustBeDrawableEverywhere() {
        val bare = CanvasOp.SetPluginElementOp("o", "a", 1, ID, props = props("status" to "queued"))
        val refused = assertIs<CanvasBatchCheck.Invalid>(CanvasBatchValidator.check(board, listOf(bare)))
        val violation = refused.violations.single().violation
        assertEquals(CanvasStateInvariant.PLUGIN_ELEMENT_FIRST_WRITE, violation.invariant)
        listOf("/elementType", "/fallback/title", "/snapshot").forEach { assertTrue(it in violation.detail, violation.detail) }

        // A link alone is enough to draw the card.
        val linkOnly = place(1).copy(snapshot = null)
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(board, listOf(linkOnly)))
        // ...and once the element is there, a state update alone is fine.
        val placed = CanvasOpProjector.project(board, listOf(place(1)))
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(placed, listOf(progress(2, 0.3))))
    }

    @Test
    fun removingAMissingPluginElementIsRefused() {
        val refused = assertIs<CanvasBatchCheck.Invalid>(CanvasBatchValidator.check(board, listOf(remove(2))))
        assertEquals(CanvasStateInvariant.PLUGIN_ELEMENT_EXISTS, refused.violations.single().violation.invariant)
        val placed = CanvasOpProjector.project(board, listOf(place(1)))
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(placed, listOf(remove(2))))
    }

    @Test
    fun anIdSharedWithAnElementOrANoteIsRefused() {
        val withNote = CanvasOpProjector.project(board, listOf(CanvasOp.SetDocumentOp("d", "a", 1, ID, """{"blocks":[]}""")))
        val refused = assertIs<CanvasBatchCheck.Invalid>(CanvasBatchValidator.check(withNote, listOf(place(2))))
        assertEquals(CanvasStateInvariant.PLUGIN_ELEMENT_DUPLICATE_ID, refused.violations.single().violation.invariant)
    }

    @Test
    fun aBoardHoldsAtMostItsLimitOfPluginElements() {
        // Built through the collection writer once, then encoded: projecting 200 whole scenes is slow on wasm.
        val root = Json.parseToJsonElement(board).jsonObject
        val entries = (0 until CanvasSceneLimits.MAX_PLUGIN_ELEMENTS).fold(root) { scene, i ->
            JsonObject(scene + (CanvasPluginElements.KEY to CanvasPluginElements.set(scene, place(i + 1L, id = "pe-$i"))))
        }
        val full = entries.toString()
        assertTrue(CanvasSceneState.violations(full).isEmpty(), "${CanvasSceneState.violations(full)}")
        val refused = assertIs<CanvasBatchCheck.Invalid>(CanvasBatchValidator.check(full, listOf(place(1_000, id = "pe-over"))))
        assertEquals(CanvasStateInvariant.PLUGIN_ELEMENT_COUNT, refused.violations.single().violation.invariant)
    }

    @Test
    fun aStoredEntryWithAFieldOutsideTheEnvelopeIsReported() {
        val placed = Json.parseToJsonElement(CanvasOpProjector.project(board, listOf(place(1)))).jsonObject
        val entry = (placed.getValue(CanvasPluginElements.KEY) as JsonArray).single().jsonObject
        val tampered = JsonObject(placed + (CanvasPluginElements.KEY to JsonArray(listOf(JsonObject(entry + ("script" to JsonPrimitive("x")))))))
        val violation = CanvasSceneState.violations(tampered.toString()).single()
        assertEquals(CanvasStateInvariant.PLUGIN_ELEMENT_DECODES, violation.invariant)
        assertEquals("$ID/script", violation.subject)
    }

    @Test
    fun theToolDescriptionDescribesThePluginOps() {
        val description = com.letta.mobile.data.canvas.CanvasToolContract.applyOps.description
        assertTrue("set_plugin_element" in description && "remove_plugin_element" in description)
    }
}
