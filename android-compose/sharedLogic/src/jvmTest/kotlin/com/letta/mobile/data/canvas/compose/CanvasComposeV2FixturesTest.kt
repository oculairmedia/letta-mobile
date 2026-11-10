package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The v2 fixtures under `commonTest/resources/canvas/compose/v2/`: `request-data-kinds.json` (a
 * dashboard of every data kind, grouped and not) is accepted and round-trips, is refused at the
 * path of any added field or unknown kind, compiles on an empty board to exactly
 * `compiled-ops.json`, and publishes with exactly `receipt.json`. A change to the drawings, the
 * reserve or placement fails here by design; the actual output is written under
 * `build/canvas-compose-v2/` to compare with and copy over the fixture.
 */
class CanvasComposeV2FixturesTest {
    private fun text(name: String): String = checkNotNull(javaClass.getResource("$DIR/$name")) { "missing fixture $name" }.readText()

    private fun fixture(name: String): JsonElement = ComposeJson.parse(text(name))

    private fun assertGolden(name: String, actual: JsonElement) {
        val canonical = ComposeJson.canonical(actual)
        val expected = ComposeJson.canonical(fixture(name))
        if (canonical != expected) {
            val out = File("build/canvas-compose-v2").apply { mkdirs() }.resolve(name)
            out.writeText(PRETTY.encodeToString(JsonElement.serializer(), actual) + "\n")
        }
        assertEquals(expected, canonical, "$name differs; the actual output is in build/canvas-compose-v2/$name")
    }

    @Test
    fun everyFixtureFileIsCoveredHere() {
        val dir = File(checkNotNull(javaClass.getResource(DIR)).toURI())
        assertEquals(setOf(REQUEST, "compiled-ops.json", "receipt.json"), dir.list()!!.filter { it.endsWith(".json") }.toSet())
    }

    @Test
    fun theRequestIsAcceptedAndRoundTrips() {
        val request = requestOf(CanvasComposeContract.decode(fixture(REQUEST)))
        val dataKinds = request.allItems().map { it.kind }.filter { it.since == 2 }.toSet()
        assertEquals(setOf(ComposeKind.TABLE, ComposeKind.STAT, ComposeKind.PROGRESS, ComposeKind.CHART), dataKinds)
        val encoded = CanvasComposeContract.json.encodeToJsonElement(ComposeRequest.serializer(), request)
        assertEquals(ComposeJson.canonical(fixture(REQUEST)), ComposeJson.canonical(encoded))
    }

    @Test
    fun aFieldAddedOrAKindUnknownAnywhereIsRefusedAtItsPath() {
        val request = fixture(REQUEST)
        ComposeJson.objectPointers(request).forEach { pointer ->
            val refusal = refusalOf(CanvasComposeContract.decode(ComposeJson.with(request, "$pointer/surprise", JsonPrimitive(true))))
            assertSingleProblem(refusal, "$pointer/surprise", ComposeProblemCode.UNKNOWN_FIELD)
        }
        ComposeJson.itemPointers(request).forEach { pointer ->
            val refusal = refusalOf(CanvasComposeContract.decode(ComposeJson.with(request, "$pointer/kind", JsonPrimitive("WIDGET"))))
            assertSingleProblem(refusal, "$pointer/kind", ComposeProblemCode.UNKNOWN_KIND)
        }
    }

    @Test
    fun asVersionOneTheRequestIsRefusedAtEveryDataKind() {
        val refusal = refusalOf(CanvasComposeContract.decode(ComposeJson.with(fixture(REQUEST), "/version", JsonPrimitive(1))))
        assertEquals(ComposeErrorCode.VALIDATION_FAILED, refusal.code)
        assertEquals(
            listOf("/items/1/children/0/kind", "/items/1/children/1/kind", "/items/1/children/2/kind", "/items/1/children/3/kind",
                "/items/2/kind", "/items/3/kind", "/items/4/kind"),
            refusal.problems.map { it.path },
        )
    }

    @Test
    fun theRequestCompilesToTheGoldenBatch() {
        val ready = readyOf(CanvasComposeCompiler.compile(fixture(REQUEST), "") { error("the request names its artifact") })
        assertGolden("compiled-ops.json", OPS_JSON.encodeToJsonElement(ListSerializer(CanvasOp.serializer()), ready.ops))
    }

    @Test
    fun theRequestPublishesWithTheGoldenReceipt() = runTest {
        val outcome = CanvasComposeService.compose(
            fixture(REQUEST), ComposeTarget(CanvasId("canvas-conversation-conv-123"), "", 7, "call-1"),
            publish = { 8 },
        )
        val done = assertIs<ComposeOutcome.Done>(outcome)
        assertGolden("receipt.json", ComposeJson.parse(done.json))
        assertEquals(2, done.receipt.version)
    }

    private companion object {
        const val DIR = "/canvas/compose/v2"
        const val REQUEST = "request-data-kinds.json"

        /** How the op log writes ops: no nulls, and an absent optional field stays absent. */
        val OPS_JSON = Json {
            encodeDefaults = false
            explicitNulls = false
        }

        val PRETTY = Json { prettyPrint = true }
    }
}
