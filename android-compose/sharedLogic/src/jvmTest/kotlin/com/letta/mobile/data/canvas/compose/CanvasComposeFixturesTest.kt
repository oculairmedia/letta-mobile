package com.letta.mobile.data.canvas.compose

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The serialized v1 fixtures under `commonTest/resources/canvas/compose/v1/` are the wire contract
 * of canvas_compose (letta-mobile-bglj6.6, plan section 5): each one decodes through the DTOs and
 * re-encodes to the same JSON, and every request fixture is refused, at the path of the change,
 * once a field is added, a kind is unknown or a cap is exceeded. Read from the JVM classpath:
 * the browser and native targets cannot read resource files.
 */
class CanvasComposeFixturesTest {
    private fun fixture(name: String): JsonElement =
        ComposeJson.parse(checkNotNull(javaClass.getResource("$DIR/$name")) { "missing fixture $name" }.readText())

    private fun <T> assertRoundTrips(name: String, serializer: KSerializer<T>): T {
        val original = fixture(name)
        val decoded = CanvasComposeContract.json.decodeFromJsonElement(serializer, original)
        val encoded = CanvasComposeContract.json.encodeToJsonElement(serializer, decoded)
        assertEquals(ComposeJson.canonical(original), ComposeJson.canonical(encoded), "$name does not round-trip")
        return decoded
    }

    @Test
    fun everyFixtureFileIsCoveredHere() {
        val dir = File(checkNotNull(javaClass.getResource(DIR)).toURI())
        // compiled-ops.json is held by the compiler's CanvasComposeCompiledGoldenTest (letta-mobile-bglj6.10);
        // placement-golden.json is held by CanvasComposePlacementGoldenTest (letta-mobile-bglj6.9).
        val ownTests = setOf("compiled-ops.json", "placement-golden.json")
        val files = dir.list()!!.filter { it.endsWith(".json") && it !in ownTests }.toSet()
        assertEquals(REQUESTS.toSet() + REFUSED_REQUESTS + OUTPUTS.keys, files)
    }

    @Test
    fun everyRequestFixtureIsAcceptedAndRoundTrips() {
        REQUESTS.forEach { name ->
            val request = requestOf(CanvasComposeContract.decode(fixture(name)))
            val encoded = CanvasComposeContract.json.encodeToJsonElement(ComposeRequest.serializer(), request)
            assertEquals(ComposeJson.canonical(fixture(name)), ComposeJson.canonical(encoded), "$name does not round-trip")
        }
    }

    @Test
    fun aReceiptWithBoardIdsNamesTheSamePiecesAsOneWithout() {
        val slim = assertRoundTrips("receipt.json", ComposeReceipt.serializer())
        val legacy = assertRoundTrips("receipt-with-ids.json", ComposeReceipt.serializer())
        fun all(receipt: ComposeReceipt) = receipt.items.flatMap { listOf(it) + it.children.orEmpty() }
        fun ids(receipt: ComposeReceipt) = all(receipt).map { it.boardId(receipt.artifactId) }
        assertEquals(ids(legacy), ids(slim))
        assertEquals(all(legacy).map { it.id }, ids(slim), "the derived id is the one an older receipt wrote")
        fun stripped(items: List<ComposeReceiptItem>): List<ComposeReceiptItem> =
            items.map { it.copy(id = null, children = it.children?.let(::stripped)) }
        assertEquals(slim, legacy.copy(items = stripped(legacy.items)), "the only difference is the ids")
    }

    @Test
    fun theDryRunFixtureAsksForADryRun() {
        assertEquals(true, requestOf(CanvasComposeContract.decode(fixture("request-dry-run.json"))).dryRun)
    }

    @Test
    fun theGuideExampleIsTheRequestFixture() {
        assertEquals(
            ComposeJson.canonical(fixture("request.json")),
            ComposeJson.canonical(ComposeJson.parse(CanvasComposeGuide.EXAMPLE_REQUEST)),
        )
    }

    @Test
    fun everyOutputFixtureRoundTripsThroughItsDto() {
        OUTPUTS.forEach { (name, serializer) -> assertRoundTrips(name, serializer) }
    }

    @Test
    fun everyRefusalFixtureEndsWithTheHint() {
        listOf("error-validation.json", "error-board-refused.json").forEach { name ->
            assertEquals(CanvasComposeContract.REFUSAL_HINT, assertRoundTrips(name, ComposeRefusal.serializer()).hint, name)
        }
    }

    @Test
    fun aFieldAddedAnywhereInARequestFixtureIsRefusedAtItsPath() {
        REQUESTS.forEach { name ->
            val request = fixture(name)
            ComposeJson.objectPointers(request).forEach { pointer ->
                val refusal = refusalOf(CanvasComposeContract.decode(ComposeJson.with(request, "$pointer/surprise", JsonPrimitive(true))))
                assertSingleProblem(refusal, "$pointer/surprise", ComposeProblemCode.UNKNOWN_FIELD)
            }
        }
    }

    @Test
    fun anUnknownKindAnywhereInARequestFixtureIsRefusedAtItsKind() {
        REQUESTS.forEach { name ->
            val request = fixture(name)
            ComposeJson.itemPointers(request).forEach { pointer ->
                val refusal = refusalOf(CanvasComposeContract.decode(ComposeJson.with(request, "$pointer/kind", JsonPrimitive("WIDGET"))))
                assertSingleProblem(refusal, "$pointer/kind", ComposeProblemCode.UNKNOWN_KIND)
            }
        }
    }

    @Test
    fun aRequestFixtureOverTheItemCapIsRefusedAtItsItems() {
        REQUESTS.forEach { name ->
            val request = fixture(name)
            val items = (request as JsonObject).getValue("items").jsonArray
            val padded = JsonArray(List(CanvasComposeContract.MAX_ITEMS + 1) { items.last() })
            assertSingleProblem(
                refusalOf(CanvasComposeContract.decode(ComposeJson.with(request, "/items", padded))),
                "/items", ComposeProblemCode.TOO_MANY_ITEMS,
            )
        }
    }

    @Test
    fun anOutputFixtureWithAnAddedFieldDoesNotDecode() {
        OUTPUTS.forEach { (name, serializer) ->
            val extended = ComposeJson.with(fixture(name), "/surprise", JsonPrimitive(true))
            assertFailsWith<SerializationException>(name) { CanvasComposeContract.json.decodeFromJsonElement(serializer, extended) }
        }
    }

    @Test
    fun theUnsupportedVersionFixtureIsRefusedAsSuch() {
        val refusal = refusalOf(CanvasComposeContract.decode(fixture("request-unsupported-version.json")))
        assertEquals(ComposeErrorCode.UNSUPPORTED_VERSION, refusal.code)
        assertSingleProblem(refusal, "/version", ComposeProblemCode.BAD_VALUE)
    }

    @Test
    fun theUnknownKindFixtureIsRefusedAtItsKind() {
        val refusal = refusalOf(CanvasComposeContract.decode(fixture("request-unknown-kind.json")))
        assertEquals(ComposeErrorCode.VALIDATION_FAILED, refusal.code)
        assertSingleProblem(refusal, "/items/1/kind", ComposeProblemCode.UNKNOWN_KIND)
        // The message is the one the validation fixture shows an agent.
        val shown = assertRoundTrips("error-validation.json", ComposeRefusal.serializer()).problems.first()
        assertEquals(shown.message, refusal.problems.single().message)
    }

    private companion object {
        const val DIR = "/canvas/compose/v1"
        // request-multi-card.json is also the request of the multi-card end-to-end gate (letta-mobile-bglj6.14).
        val REQUESTS = listOf("request.json", "request-dry-run.json", "request-multi-card.json")
        val REFUSED_REQUESTS = setOf("request-unsupported-version.json", "request-unknown-kind.json")
        val OUTPUTS: Map<String, KSerializer<*>> = mapOf(
            "receipt.json" to ComposeReceipt.serializer(),
            "receipt-dry-run.json" to ComposeReceipt.serializer(),
            // A receipt as written before letta-mobile-bglj6.14, with each item's board id: still read.
            "receipt-with-ids.json" to ComposeReceipt.serializer(),
            "error-validation.json" to ComposeRefusal.serializer(),
            "error-board-refused.json" to ComposeRefusal.serializer(),
        )
    }
}
