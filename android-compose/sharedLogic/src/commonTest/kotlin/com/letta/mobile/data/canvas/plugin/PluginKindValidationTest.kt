package com.letta.mobile.data.canvas.plugin

import com.letta.mobile.data.canvas.CanvasBatchCheck
import com.letta.mobile.data.canvas.CanvasBatchValidator
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasStateInvariant
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.ID
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.TYPE
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.place
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.progress
import com.letta.mobile.data.canvas.plugin.CanvasPluginElementFixtures.props
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.2: plugin element props held to their kind's schema on the one validator path,
 * refused at `/props/...` pointers; a kind the host does not know, or a version of it, accepted
 * with a WARN (D11); the envelope's caps still refusing whatever the catalog.
 */
class PluginKindValidationTest {
    private val board = CanvasOpProjector.emptySceneJson()

    private val widgetProps: JsonObject = Json.parseToJsonElement(
        """
        {"type":"object","additionalProperties":false,"required":["status"],"properties":{
          "status":{"enum":["queued","running","done","failed"]},
          "progress":{"type":"number","minimum":0,"maximum":1},
          "label":{"type":"string","maxLength":128}}}
        """,
    ).jsonObject

    private val catalog = InMemoryPluginKindCatalog(listOf(PluginKindSpec(TYPE, 1, widgetProps, PluginKindSize(320f, 240f))))

    private fun refusal(sceneJson: String, op: CanvasOp): CanvasBatchCheck.Invalid =
        assertIs<CanvasBatchCheck.Invalid>(CanvasBatchValidator.check(sceneJson, listOf(op), catalog), "expected a refusal of $op")

    private fun subjects(refused: CanvasBatchCheck.Invalid) = refused.violations.map { it.violation.subject }

    private fun unknownKindWarnings() = Telemetry.snapshot().filter { it.name == PluginKindProps.UNKNOWN_KIND_EVENT }

    @Test
    fun registeredKindsAreFoundByTypeAndVersion() {
        assertEquals(widgetProps, catalog.schemaFor(TYPE, 1))
        assertNull(catalog.schemaFor(TYPE, 2))
        assertNull(catalog.schemaFor("ext:someone.else/gadget", 1))
        assertTrue(catalog.knows("letta.example"))
        assertEquals(PluginKindSize(320f, 240f), catalog.spec(TYPE, 1)?.defaultSize)
    }

    @Test
    fun aKindThatCannotBeRegisteredRefusesTheCatalog() {
        assertFailsWith<IllegalArgumentException> { InMemoryPluginKindCatalog(listOf(PluginKindSpec("widget", 1, widgetProps))) }
        assertFailsWith<IllegalArgumentException> { InMemoryPluginKindCatalog(listOf(PluginKindSpec(TYPE, 0, widgetProps))) }
        assertFailsWith<IllegalArgumentException> { InMemoryPluginKindCatalog(listOf(PluginKindSpec(TYPE, 1, JsonObject(emptyMap())))) }
        assertFailsWith<IllegalArgumentException> {
            InMemoryPluginKindCatalog(listOf(PluginKindSpec(TYPE, 1, widgetProps), PluginKindSpec(TYPE, 1, widgetProps)))
        }
    }

    @Test
    fun validPropsPass() {
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(board, listOf(place(1)), catalog))
        val placed = CanvasOpProjector.project(board, listOf(place(1)))
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(placed, listOf(progress(2, 0.4)), catalog))
    }

    @Test
    fun aValueOutsideTheEnumIsRefusedAtItsPointerWithTheKindsExample() {
        val refused = refusal(board, place(1).copy(props = props("status" to "paused")))
        assertEquals(listOf("$ID/props/status"), subjects(refused))
        assertEquals(CanvasStateInvariant.PLUGIN_ELEMENT_SHAPE, refused.violations.single().violation.invariant)
        val detail = refused.violations.single().violation.detail
        assertTrue("$TYPE v1 props look like {\"status\":\"queued\"" in detail, detail)
    }

    @Test
    fun aKeyTheSchemaDoesNotNameIsRefused() {
        val refused = refusal(board, place(1).copy(props = props("status" to "queued", "colour" to "red")))
        assertEquals(listOf("$ID/props/colour"), subjects(refused))
    }

    @Test
    fun everyProblemOfTheWriteIsNamed() {
        val refused = refusal(board, place(1).copy(props = props("progress" to 2)))
        assertEquals(listOf("$ID/props/status", "$ID/props/progress"), subjects(refused))
    }

    @Test
    fun aPropsUpdateIsHeldToTheKindTheElementHasOnTheBoard() {
        val placed = CanvasOpProjector.project(board, listOf(place(1)))
        val refused = refusal(placed, progress(2, 1.5))
        assertEquals(listOf("$ID/props/progress"), subjects(refused))
        // ...and to the kind a write earlier in the same batch gives it.
        val batch = listOf(place(1), progress(2, 1.5))
        val inBatch = assertIs<CanvasBatchCheck.Invalid>(CanvasBatchValidator.check(board, batch, catalog))
        assertEquals(listOf("$ID/props/progress"), subjects(inBatch))
    }

    @Test
    fun anUnknownPluginIsAcceptedWithAWarning() {
        Telemetry.clear()
        val unknown = place(1).copy(elementType = "ext:someone.else/gadget", props = props("anything" to "goes"))
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(board, listOf(unknown), catalog))
        val warning = unknownKindWarnings().single()
        assertEquals(Telemetry.Level.WARN, warning.level)
        assertEquals("unknown", warning.attrs["plugin"])
        assertEquals("ext:someone.else/gadget", warning.attrs["type"])
    }

    @Test
    fun aKnownPluginAtAVersionWithNoSchemaHereIsAcceptedWithAWarning() {
        Telemetry.clear()
        val newer = place(1).copy(v = 2, props = props("status" to "paused", "extra" to 1))
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(board, listOf(newer), catalog))
        val warning = unknownKindWarnings().single()
        assertEquals("known", warning.attrs["plugin"])
        assertEquals(2, warning.attrs["v"])

        Telemetry.clear()
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check(board, listOf(place(1)), catalog))
        assertTrue(unknownKindWarnings().isEmpty())
    }

    @Test
    fun theSizeCapRefusesWhateverTheCatalog() {
        val big = place(1).copy(props = props("status" to "queued", "label" to "x".repeat(5_000)))
        listOf(PluginKindCatalog.Empty, catalog).forEach { kinds ->
            val refused = assertIs<CanvasBatchCheck.Invalid>(CanvasBatchValidator.check(board, listOf(big), kinds))
            assertEquals(listOf("$ID/props"), subjects(refused), "$kinds")
        }
    }

    @Test
    fun theKindsOfAManifestsElementsMapRegister() {
        val elements = Json.parseToJsonElement(
            """{"widget":{"schemaVersion":2,"props":$widgetProps,"defaultSize":{"width":320,"height":240}}}""",
        ).jsonObject
        val specs = InMemoryPluginKindCatalog.specsOf("letta.example", elements)
        assertEquals(listOf(PluginKindSpec(TYPE, 2, widgetProps, PluginKindSize(320f, 240f))), specs)
        assertEquals(widgetProps, InMemoryPluginKindCatalog(specs).schemaFor(TYPE, 2))
    }
}
