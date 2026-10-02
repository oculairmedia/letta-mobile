package com.letta.mobile.data.canvas.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The canvas_compose v1 wire contract (letta-mobile-bglj6.6): what a request may say, how it is
 * read, and what comes back. The fixture files themselves are held to it in CanvasComposeFixturesTest.
 */
class CanvasComposeContractTest {
    private val example = ComposeJson.parse(CanvasComposeGuide.EXAMPLE_REQUEST)

    @Test
    fun theCatalogAndItsCapsAreTheOnesThePlanFixed() {
        assertEquals("letta.canvas.compose", CanvasComposeContract.CATALOG)
        assertEquals(1, CanvasComposeContract.VERSION)
        assertEquals(24, CanvasComposeContract.MAX_ITEMS)
        assertEquals(40, CanvasComposeContract.MAX_CHECKLIST_ITEMS)
        assertEquals(4_000, CanvasComposeContract.MAX_MARKDOWN_CHARS)
        assertEquals(8, CanvasComposeContract.MAX_CARD_FIELDS)
        assertEquals(500, CanvasComposeContract.MAX_CARD_BODY_CHARS)
        assertEquals(65_536, CanvasComposeContract.MAX_REQUEST_BYTES)
        assertEquals(1, CanvasComposeContract.MAX_GROUP_DEPTH)
    }

    @Test
    fun everyKindHasItsWidth() {
        assertEquals(320f, CanvasComposeContract.width(ComposeKind.NOTE))
        assertEquals(320f, CanvasComposeContract.width(ComposeKind.CHECKLIST))
        assertEquals(320f, CanvasComposeContract.width(ComposeKind.CARD))
        assertEquals(480f, CanvasComposeContract.width(ComposeKind.TEXT, ComposeTextSize.HEADING))
        assertEquals(320f, CanvasComposeContract.width(ComposeKind.TEXT, ComposeTextSize.BODY))
    }

    @Test
    fun keysAreShortLowercaseIds() {
        listOf("a", "0", "heading", "self-care", "i1_c2", "a".repeat(32)).forEach { assertTrue(CanvasComposeContract.isKey(it), it) }
        listOf("", "Heading", "-lead", "_lead", "has space", "dot.ted", "a".repeat(33), "é").forEach {
            assertFalse(CanvasComposeContract.isKey(it), it)
        }
    }

    @Test
    fun artifactIdsAreLongerKeys() {
        listOf("weekend-plan", "a-0123456789ab", "a".repeat(48)).forEach { assertTrue(CanvasComposeContract.isArtifactId(it), it) }
        listOf("", "Weekend", "-x", "a".repeat(49)).forEach { assertFalse(CanvasComposeContract.isArtifactId(it), it) }
    }

    @Test
    fun coloursAreTheJsonCanvasPresetsOrRgb() {
        assertEquals(listOf("red", "orange", "yellow", "green", "cyan", "purple"), CanvasComposeContract.COLOR_PRESETS)
        (CanvasComposeContract.COLOR_PRESETS + listOf("#fff59d", "#C8E6C9")).forEach { assertTrue(CanvasComposeContract.isColor(it), it) }
        listOf("blue", "Red", "#fff", "#fff59d00", "fff59d", "#ggg000", "").forEach { assertFalse(CanvasComposeContract.isColor(it), it) }
    }

    @Test
    fun theGuideExampleDecodesToTheItemsItSays() {
        val request = requestOf(CanvasComposeContract.decode(example))
        assertEquals("weekend-plan", request.artifactId)
        assertEquals(listOf(ComposeKind.TEXT, ComposeKind.CHECKLIST, ComposeKind.NOTE, ComposeKind.GROUP), request.items.map { it.kind })
        val shopping = assertIs<ComposeItem.Checklist>(request.items[1])
        assertEquals(listOf(null, true, null), shopping.items.map { it.checked })
        val group = assertIs<ComposeItem.Group>(request.items[3])
        assertEquals(listOf("walk", "read"), group.children.map { it.key })
        assertEquals(ComposeCardField("When", "Sat 09:00"), assertIs<ComposeItem.Card>(group.children[0]).fields!!.first())
        assertEquals(ComposeTextSize.HEADING, assertIs<ComposeItem.Text>(request.items[0]).size)
    }

    @Test
    fun aDecodedRequestEncodesBackToTheSameJson() {
        val request = requestOf(CanvasComposeContract.decode(example))
        val encoded = CanvasComposeContract.json.encodeToJsonElement(ComposeRequest.serializer(), request)
        assertEquals(ComposeJson.canonical(example), ComposeJson.canonical(encoded))
    }

    @Test
    fun anAbsentOptionalFieldIsNotWrittenAsNull() {
        val encoded = CanvasComposeContract.json.encodeToString(
            ComposeRequest.serializer(),
            ComposeRequest(items = listOf(ComposeItem.Note(markdown = "hi"))),
        )
        assertEquals("""{"items":[{"kind":"NOTE","markdown":"hi"}]}""", encoded)
    }

    @Test
    fun anUnknownKindIsARefusalAtItsPathNotAnException() {
        val request = ComposeJson.with(example, "/items/2/kind", JsonPrimitive("STICKY"))
        val refusal = refusalOf(CanvasComposeContract.decode(request))
        assertEquals(ComposeErrorCode.VALIDATION_FAILED, refusal.code)
        assertSingleProblem(refusal, "/items/2/kind", ComposeProblemCode.UNKNOWN_KIND)
        assertEquals("'STICKY' is not a kind; use NOTE, CHECKLIST, CARD, TEXT or GROUP", refusal.problems.single().message)
        assertFalse(refusal.ok)
        assertEquals(CanvasComposeContract.REFUSAL_HINT, refusal.hint)
    }

    @Test
    fun anotherVersionOrCatalogIsUnsupportedNotMalformed() {
        val version = refusalOf(CanvasComposeContract.decode(ComposeJson.with(example, "/version", JsonPrimitive(2))))
        assertEquals(ComposeErrorCode.UNSUPPORTED_VERSION, version.code)
        assertSingleProblem(version, "/version", ComposeProblemCode.BAD_VALUE)
        assertTrue("supported: 1" in version.problems.single().message)

        val catalog = refusalOf(CanvasComposeContract.decode(ComposeJson.with(example, "/catalog", JsonPrimitive("a2ui.basic"))))
        assertEquals(ComposeErrorCode.UNSUPPORTED_VERSION, catalog.code)
        assertSingleProblem(catalog, "/catalog", ComposeProblemCode.BAD_VALUE)
    }

    @Test
    fun catalogAndVersionMayBeLeftOut() {
        val bare = JsonObject((example as JsonObject) - "catalog" - "version")
        assertEquals(null, requestOf(CanvasComposeContract.decode(bare)).version)
    }

    @Test
    fun groupChildrenCountTowardsTheItemCap() {
        val notes = (1..12).map { buildJsonObject { put("kind", "NOTE"); put("markdown", "n$it") } }
        val group = buildJsonObject { put("kind", "GROUP"); put("children", JsonArray(notes)) }
        val atCap = buildJsonObject { put("items", JsonArray(listOf(group, notes.first()) + notes.drop(1).take(10))) }
        requestOf(CanvasComposeContract.decode(atCap))

        val overCap = buildJsonObject { put("items", JsonArray(listOf(group) + notes)) }
        val refusal = refusalOf(CanvasComposeContract.decode(overCap))
        assertSingleProblem(refusal, "/items", ComposeProblemCode.TOO_MANY_ITEMS)
        assertTrue("got 25" in refusal.problems.single().message)
    }

    @Test
    fun aKeyIsUniqueAcrossTheWholeRequestGroupsIncluded() {
        val request = ComposeJson.with(example, "/items/3/children/1/key", JsonPrimitive("meals"))
        val refusal = refusalOf(CanvasComposeContract.decode(request))
        assertSingleProblem(refusal, "/items/3/children/1/key", ComposeProblemCode.DUPLICATE_KEY)
        assertTrue("/items/2" in refusal.problems.single().message)
    }

    @Test
    fun textThatIsNotJsonIsRefused() {
        assertSingleProblem(refusalOf(CanvasComposeContract.decode("{not json")), "", ComposeProblemCode.WRONG_TYPE)
        assertSingleProblem(refusalOf(CanvasComposeContract.decode("[1]")), "", ComposeProblemCode.WRONG_TYPE)
    }

    @Test
    fun aRequestOverTheByteCapIsRefusedBeforeItIsRead() {
        val huge = """{"items":[{"kind":"NOTE","markdown":"${"x".repeat(CanvasComposeContract.MAX_REQUEST_BYTES)}"}]}"""
        assertSingleProblem(refusalOf(CanvasComposeContract.decode(huge)), "", ComposeProblemCode.TOO_LONG)
        assertSingleProblem(refusalOf(CanvasComposeContract.decode(ComposeJson.parse(huge))), "", ComposeProblemCode.TOO_LONG)
    }

    @Test
    fun aReceiptAndARefusalSayWhichCatalogTheyAreFrom() {
        val receipt = CanvasComposeContract.json.encodeToJsonElement(
            ComposeReceipt.serializer(),
            ComposeReceipt(
                artifactId = "a", canvasId = "c", status = ComposeStatus.DRY_RUN,
                items = listOf(ComposeReceiptItem("i1", ComposeKind.NOTE, "cmp-a-i1")),
            ),
        )
        assertEquals(
            """{"artifact_id":"a","canvas_id":"c","catalog":"letta.canvas.compose","items":[{"id":"cmp-a-i1","key":"i1","kind":"NOTE"}],""" +
                """"ok":true,"status":"dry_run","version":1,"warnings":[]}""",
            ComposeJson.canonical(receipt),
        )
        val refusal = CanvasComposeContract.refusal(
            ComposeErrorCode.BOARD_REFUSED, listOf(ComposeProblem("/items/0", "document.size", "too big")),
        )
        assertEquals(
            """{"catalog":"letta.canvas.compose","code":"BOARD_REFUSED","hint":${JsonPrimitive(CanvasComposeContract.REFUSAL_HINT)},""" +
                """"ok":false,"problems":[{"code":"document.size","message":"too big","path":"/items/0"}],"version":1}""",
            ComposeJson.canonical(CanvasComposeContract.json.encodeToJsonElement(ComposeRefusal.serializer(), refusal)),
        )
    }
}
