package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasBatchCheck
import com.letta.mobile.data.canvas.CanvasBatchValidator
import com.letta.mobile.data.canvas.CanvasComposeProvenance
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canvas.compose compiler (letta-mobile-bglj6.10): what a request becomes on the board, every
 * refusal at its JSON pointer, ids that make a retry idempotent, and batches the board's own
 * validator accepts. The exact op batch for the request fixture is pinned on the JVM
 * (CanvasComposeCompiledGoldenTest, `compiled-ops.json`).
 */
class CanvasComposeCompilerTest {
    // ---- What the request fixture becomes -------------------------------------------------------

    @Test
    fun theRequestFixtureCompilesToGroupFramesThenDocumentsThenTexts() {
        val ready = readyOf(compileText(WEEKEND_PLAN))
        assertEquals(
            listOf(
                "add_element cmp-weekend-plan-selfcare",
                "set_document cmp-weekend-plan-shopping",
                "set_document cmp-weekend-plan-meals",
                "set_document cmp-weekend-plan-walk",
                "set_document cmp-weekend-plan-read",
                "add_element cmp-weekend-plan-heading",
                "add_element cmp-weekend-plan-selfcare-label",
            ),
            ready.ops.map(::describe),
        )
        assertEquals(
            listOf("/items/3", "/items/1", "/items/2", "/items/3/children/0", "/items/3/children/1", "/items/0", "/items/3"),
            ready.itemPaths,
        )
        assertFalse(ready.dryRun)
        assertFalse(ready.alreadyPublished)
    }

    @Test
    fun opIdentityIsLeftForThePublisherToStamp() {
        readyOf(compileText(WEEKEND_PLAN)).ops.forEach { op ->
            assertEquals("", op.opId)
            assertEquals("", op.actorId)
            assertEquals(0L, op.lamport)
            assertFalse(op is CanvasOp.BatchOp)
        }
    }

    @Test
    fun everyDocumentIsAutoOwnedFramedAndCarriesItsProvenance() {
        val documents = readyOf(compileText(WEEKEND_PLAN)).ops.filterIsInstance<CanvasOp.SetDocumentOp>()
        assertEquals(listOf("shopping", "meals", "walk", "read"), documents.map { it.compose?.key })
        documents.forEach { document ->
            assertEquals(CanvasGeometryOwner.AUTO, document.owner)
            val frame = assertIs<CanvasDocumentFrame>(document.frame)
            assertEquals(CanvasComposeContract.NOTE_WIDTH, frame.width)
            assertTrue(frame.height >= CanvasComposeReserve.MIN_RESERVE)
            assertEquals("cmp-weekend-plan-${document.compose?.key}", document.documentId)
        }
        assertEquals(
            CanvasComposeProvenance("weekend-plan", "shopping", "CHECKLIST", CanvasComposeContract.CATALOG, CanvasComposeContract.VERSION),
            documents.first().compose,
        )
        assertEquals(listOf("Shopping", "Meals", "Walk", null), documents.map { it.title })
    }

    @Test
    fun aDocumentIsBookedTheHeightTheReserveGivesItsContent() {
        val meals = readyOf(compileText(WEEKEND_PLAN)).ops.filterIsInstance<CanvasOp.SetDocumentOp>().single { it.compose?.key == "meals" }
        assertEquals(CanvasComposeReserve.reserveDocument(meals.documentJson, CanvasComposeContract.NOTE_WIDTH), meals.frame?.height)
    }

    @Test
    fun textAndGroupsAreDrawBoxElementsWithComposeProvenance() {
        val elements = readyOf(compileText(WEEKEND_PLAN)).ops.filterIsInstance<CanvasOp.AddElementOp>()
            .associate { it.elementId to ComposeJson.parse(it.elementJson).jsonObject }
        val heading = elements.getValue("cmp-weekend-plan-heading")
        assertEquals("Text", heading.string("type"))
        assertEquals("80.0,80.0", heading.string("textTopLeft"))
        assertEquals("480.0", heading.getValue("wrapWidth").toString())
        assertEquals("36.0", heading.getValue("fontSize").toString())
        assertEquals("LEFT", heading.string("alignment"))
        assertEquals("sans", heading.string("fontFamilyKey"))
        assertEquals("2", heading.getValue("zIndex").toString())
        assertEquals("TEXT", heading.getValue("_compose").jsonObject.string("kind"))

        val frame = elements.getValue("cmp-weekend-plan-selfcare")
        assertEquals("RECTANGLE", frame.string("shapeType"))
        assertEquals("0", frame.getValue("zIndex").toString())
        assertEquals("GROUP", frame.getValue("_compose").jsonObject.string("kind"))

        val label = elements.getValue("cmp-weekend-plan-selfcare-label")
        assertEquals("Self-care", label.string("text"))
        assertEquals("18.0", label.getValue("fontSize").toString())
        assertEquals("1", label.getValue("zIndex").toString())
        assertEquals("selfcare", label.getValue("_compose").jsonObject.string("key"))
    }

    @Test
    fun theGroupFrameEnclosesItsChildrenAndItsLabel() {
        val ready = readyOf(compileText(WEEKEND_PLAN))
        val frame = ready.ops.filterIsInstance<CanvasOp.AddElementOp>().single { it.elementId == "cmp-weekend-plan-selfcare" }
        val (topLeft, bottomRight) = (ComposeJson.parse(frame.elementJson).jsonObject.getValue("points") as JsonArray).map { point ->
            point.jsonPrimitive.content.split(",").map { it.toFloat() }
        }
        ready.ops.filterIsInstance<CanvasOp.SetDocumentOp>().filter { it.compose?.key in setOf("walk", "read") }.forEach { child ->
            val f = child.frame!!
            assertTrue(f.x >= topLeft[0] && f.y >= topLeft[1] && f.x + f.width <= bottomRight[0] && f.y + f.height <= bottomRight[1], "$f")
        }
    }

    @Test
    fun theReceiptListsEveryItemByKeyKindAndBoardId() {
        val ready = readyOf(compileText(WEEKEND_PLAN))
        val receipt = ready.receipt(ComposeBoard.CANVAS, ComposeStatus.PUBLISHED, 42)
        assertEquals("weekend-plan", receipt.artifactId)
        assertEquals("Weekend plan", receipt.title)
        assertEquals(42L, receipt.revision)
        assertEquals(
            listOf(
                ComposeReceiptItem("heading", ComposeKind.TEXT, "cmp-weekend-plan-heading"),
                ComposeReceiptItem("shopping", ComposeKind.CHECKLIST, "cmp-weekend-plan-shopping", count = 3),
                ComposeReceiptItem("meals", ComposeKind.NOTE, "cmp-weekend-plan-meals"),
                ComposeReceiptItem(
                    "selfcare", ComposeKind.GROUP, "cmp-weekend-plan-selfcare",
                    children = listOf(
                        ComposeReceiptItem("walk", ComposeKind.CARD, "cmp-weekend-plan-walk"),
                        ComposeReceiptItem("read", ComposeKind.NOTE, "cmp-weekend-plan-read"),
                    ),
                ),
            ),
            receipt.items,
        )
        // The bounds are the placement's: everything the artifact put on the board.
        val rects = ready.ops.mapNotNull { (it as? CanvasOp.SetDocumentOp)?.frame }
        val bounds = receipt.bounds!!
        rects.forEach { assertTrue(it.x >= bounds.x && it.y >= bounds.y && it.x + it.width <= bounds.x + bounds.width && it.y + it.height <= bounds.y + bounds.height) }
        assertEquals(80f, bounds.x)
        assertEquals(80f, bounds.y)
    }

    // ---- Every compiled batch is one the board takes --------------------------------------------

    @Test
    fun everyCompiledBatchPassesTheBatchValidatorOnAnEmptyBoard() {
        requests().forEach { (name, request) ->
            val ready = readyOf(compileText(request))
            assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check("", ready.ops), name)
        }
    }

    @Test
    fun everyCompiledBatchPassesTheBatchValidatorBesideAnUnrelatedNote() {
        requests().forEach { (name, request) ->
            val board = ComposeBoard().apply {
                addNote("mine", CanvasDocumentFrame(100f, 100f, 320f, 200f))
                addBox("box", 500f, 120f, 700f, 260f)
            }
            val ready = readyOf(compileText(request, board.sceneJson))
            val check = CanvasBatchValidator.check(board.sceneJson, ready.ops)
            assertIs<CanvasBatchCheck.Valid>(check, "$name: $check")
        }
    }

    // ---- Refusals, each at its pointer -----------------------------------------------------------

    @Test
    fun anUnknownKindIsRefusedAtItsKind() {
        val refusal = refusedOf(compileText("""{"items":[{"kind":"NOTE","markdown":"ok"},{"kind":"STICKY","text":"x"}]}"""))
        assertEquals(ComposeErrorCode.VALIDATION_FAILED, refusal.code)
        assertSingleProblem(refusal, "/items/1/kind", ComposeProblemCode.UNKNOWN_KIND)
    }

    @Test
    fun coordinatesFromTheModelAreUnknownFields() {
        listOf("x", "y", "frame", "width", "height", "position").forEach { field ->
            val refusal = refusedOf(compileText("""{"items":[{"kind":"NOTE","markdown":"ok","$field":0}]}"""))
            assertSingleProblem(refusal, "/items/0/$field", ComposeProblemCode.UNKNOWN_FIELD)
        }
        assertSingleProblem(
            refusedOf(compileText("""{"items":[{"kind":"GROUP","children":[{"kind":"TEXT","text":"t","size":"body","x":0}]}]}""")),
            "/items/0/children/0/x", ComposeProblemCode.UNKNOWN_FIELD,
        )
    }

    @Test
    fun unsupportedMarkdownIsRefusedAtItsFieldWhereverTheItemIs() {
        assertProblemsAt(
            refusedOf(compileText("""{"items":[{"kind":"NOTE","markdown":"ok"},{"kind":"NOTE","markdown":"Hello <b>there</b>"}]}""")),
            "/items/1/markdown", ComposeProblemCode.UNSUPPORTED_MARKDOWN,
        )
        assertProblemsAt(
            refusedOf(compileText("""{"items":[{"kind":"GROUP","children":[{"kind":"NOTE","markdown":"ok"},{"kind":"NOTE","markdown":"| a | b |\n|---|---|"}]}]}""")),
            "/items/0/children/1/markdown", ComposeProblemCode.UNSUPPORTED_MARKDOWN,
        )
        assertProblemsAt(
            refusedOf(compileText("""{"items":[{"kind":"CARD","title":"T","markdown":"![x](y.png)"}]}""")),
            "/items/0/markdown", ComposeProblemCode.UNSUPPORTED_MARKDOWN,
        )
    }

    @Test
    fun everyProblemOfARequestIsReportedTogether() {
        val refusal = refusedOf(compileText("""{"items":[{"kind":"NOTE","markdown":"<i>a</i>"},{"kind":"NOTE","markdown":"ok"},{"kind":"CARD","title":"T","markdown":"<u>b</u>"}]}"""))
        assertEquals(listOf("/items/0/markdown", "/items/2/markdown"), refusal.problems.map { it.path }.distinct())
        assertEquals(CanvasComposeContract.REFUSAL_HINT, refusal.hint)
    }

    @Test
    fun capsAndShapesAreRefusedAtTheirPointers() {
        val tooManyEntries = buildJsonArray { repeat(CanvasComposeContract.MAX_CHECKLIST_ITEMS + 1) { add(buildJsonObject { put("text", "t$it") }) } }
        assertSingleProblem(
            refusedOf(compileText("""{"items":[{"kind":"NOTE","markdown":"ok"},{"kind":"CHECKLIST","items":$tooManyEntries}]}""")),
            "/items/1/items", ComposeProblemCode.TOO_MANY_ITEMS,
        )
        assertSingleProblem(refusedOf(compileText("""{"items":[{"kind":"NOTE","markdown":"ok","color":"blue"}]}""")), "/items/0/color", ComposeProblemCode.BAD_COLOR)
        assertSingleProblem(refusedOf(compileText("""{"items":[{"kind":"NOTE","key":"Bad Key","markdown":"ok"}]}""")), "/items/0/key", ComposeProblemCode.BAD_KEY)
        assertSingleProblem(refusedOf(compileText("""{"artifact_id":"-no","items":[{"kind":"NOTE","markdown":"ok"}]}""")), "/artifact_id", ComposeProblemCode.BAD_KEY)
        assertSingleProblem(
            refusedOf(compileText("""{"items":[{"kind":"NOTE","markdown":"${"a".repeat(CanvasComposeContract.MAX_MARKDOWN_CHARS + 1)}"}]}""")),
            "/items/0/markdown", ComposeProblemCode.TOO_LONG,
        )
        assertSingleProblem(refusedOf(compileText("""{"items":[{"kind":"CARD","markdown":"x"}]}""")), "/items/0/title", ComposeProblemCode.MISSING_FIELD)
        assertSingleProblem(
            refusedOf(compileText("""{"items":[{"kind":"GROUP","children":[{"kind":"GROUP","children":[{"kind":"NOTE","markdown":"x"}]}]}]}""")),
            "/items/0/children/0/kind", ComposeProblemCode.NESTING_TOO_DEEP,
        )
    }

    @Test
    fun anotherVersionIsUnsupported() {
        val refusal = refusedOf(compileText("""{"version":2,"items":[{"kind":"NOTE","markdown":"x"}]}"""))
        assertEquals(ComposeErrorCode.UNSUPPORTED_VERSION, refusal.code)
        assertSingleProblem(refusal, "/version", ComposeProblemCode.BAD_VALUE)
    }

    // ---- Keys and ids ---------------------------------------------------------------------------

    @Test
    fun itemsWithoutAKeyAreNamedByTheirPlaceInTheRequest() {
        val ready = readyOf(
            compileText(
                """{"artifact_id":"plan","items":[{"kind":"NOTE","markdown":"a"},{"kind":"TEXT","text":"t","size":"body"},""" +
                    """{"kind":"GROUP","children":[{"kind":"NOTE","markdown":"b"},{"kind":"NOTE","key":"named","markdown":"c"}]}]}""",
            ),
        )
        val receipt = ready.receipt(ComposeBoard.CANVAS, ComposeStatus.PUBLISHED, 1)
        assertEquals(listOf("i0", "i1", "i2"), receipt.items.map { it.key })
        assertEquals(listOf("i2-c0", "named"), receipt.items[2].children!!.map { it.key })
        assertEquals(
            setOf("cmp-plan-i0", "cmp-plan-i1", "cmp-plan-i2", "cmp-plan-i2-c0", "cmp-plan-named"),
            ready.ops.map(::idOf).toSet(),
        )
    }

    @Test
    fun aGivenKeyTwiceIsADuplicate() {
        assertSingleProblem(
            refusedOf(compileText("""{"items":[{"kind":"NOTE","key":"a","markdown":"x"},{"kind":"GROUP","children":[{"kind":"NOTE","key":"a","markdown":"y"}]}]}""")),
            "/items/1/children/0/key", ComposeProblemCode.DUPLICATE_KEY,
        )
    }

    @Test
    fun aGivenKeyThatTakesAnotherItemsDefaultIsADuplicateAtTheGivenKey() {
        assertSingleProblem(
            refusedOf(compileText("""{"items":[{"kind":"NOTE","markdown":"x"},{"kind":"NOTE","key":"i0","markdown":"y"}]}""")),
            "/items/1/key", ComposeProblemCode.DUPLICATE_KEY,
        )
    }

    @Test
    fun aKeyThatTakesAGroupLabelsIdIsADuplicate() {
        assertSingleProblem(
            refusedOf(compileText("""{"items":[{"kind":"GROUP","key":"g","label":"G","children":[{"kind":"NOTE","key":"g-label","markdown":"y"}]}]}""")),
            "/items/0/children/0/key", ComposeProblemCode.DUPLICATE_KEY,
        )
        // An unlabelled group makes no label, so the key is free.
        readyOf(compileText("""{"items":[{"kind":"GROUP","key":"g","children":[{"kind":"NOTE","key":"g-label","markdown":"y"}]}]}"""))
    }

    @Test
    fun theArtifactIdIsTheRequestsElseTheFallback() {
        assertEquals("mine", readyOf(compileText("""{"artifact_id":"mine","items":[{"kind":"NOTE","markdown":"x"}]}""")).artifactId)
        assertEquals("a-0123456789ab", readyOf(compileText("""{"items":[{"kind":"NOTE","markdown":"x"}]}""", fallback = "a-0123456789ab")).artifactId)
    }

    @Test
    fun aDerivedArtifactIdIsTheToolCallsDigest() {
        // sha256("call-1") = 3a0e6cbb...; the first 12 hex digits name the artifact.
        assertEquals("a-" + hex(Sha256.digest("call-1".encodeToByteArray())).take(12), CanvasComposeIds.derived("call-1"))
        assertEquals(CanvasComposeIds.derived("call-1"), CanvasComposeIds.derived("call-1"))
        assertNotEquals(CanvasComposeIds.derived("call-1"), CanvasComposeIds.derived("call-2"))
        assertTrue(CanvasComposeContract.isArtifactId(CanvasComposeIds.derived("call-1")))
        assertTrue(CanvasComposeContract.isArtifactId(CanvasComposeIds.derived(null)))
        assertNotEquals(CanvasComposeIds.derived(null), CanvasComposeIds.derived(null))
    }

    @Test
    fun sha256MatchesTheStandardVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(Sha256.digest(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex(Sha256.digest("abc".encodeToByteArray())))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    // ---- Idempotency ----------------------------------------------------------------------------

    @Test
    fun aRetryOfAPublishedArtifactIsIdempotent() {
        val board = ComposeBoard()
        val first = readyOf(compileText(WEEKEND_PLAN, board.sceneJson))
        board.publishChecked(first.ops)
        val published = board.sceneJson

        val retry = readyOf(compileText(WEEKEND_PLAN, board.sceneJson))
        assertTrue(retry.alreadyPublished)
        assertEquals(emptyList(), retry.ops)
        assertEquals(first.items, retry.items)
        assertEquals(first.bounds, retry.bounds)
        assertEquals(published, board.sceneJson)
        // No duplicate pieces: one of each id on the board.
        val ids = CanvasOpProjector.documentsOf(board.sceneJson).map { it.id } + elementIds(board.sceneJson)
        assertEquals(ids.distinct(), ids)
        assertEquals(first.ops.map(::idOf).toSet(), ids.toSet())
    }

    @Test
    fun aRetryAfterAPersonMovedAPieceIsStillTheSameArtifact() {
        val board = ComposeBoard()
        board.publishChecked(readyOf(compileText(WEEKEND_PLAN)).ops)
        val meals = CanvasOpProjector.documentsOf(board.sceneJson).single { it.id == "cmp-weekend-plan-meals" }
        board.publishChecked(
            listOf(
                CanvasOp.SetDocumentOp(
                    "", "", 0, meals.id, meals.json,
                    frame = meals.frame!!.copy(x = 2000f), owner = CanvasGeometryOwner.USER,
                ),
            ),
        )
        val retry = readyOf(compileText(WEEKEND_PLAN, board.sceneJson))
        assertTrue(retry.alreadyPublished)
        assertEquals(2000f + 320f, retry.bounds!!.x + retry.bounds!!.width)
    }

    @Test
    fun theSameArtifactIdWithOtherContentIsRefused() {
        val board = ComposeBoard()
        board.publishChecked(readyOf(compileText(WEEKEND_PLAN)).ops)
        val changed = WEEKEND_PLAN.replace("Milk", "Oat milk")
        val refusal = refusedOf(compileText(changed, board.sceneJson))
        assertEquals(ComposeErrorCode.ARTIFACT_EXISTS, refusal.code)
        assertEquals(listOf("/artifact_id" to "ARTIFACT_EXISTS"), refusal.problems.map { it.path to it.code })
        // With fewer items, too: a retry is the WHOLE artifact.
        val fewer = """{"artifact_id":"weekend-plan","items":[{"kind":"TEXT","key":"heading","text":"Weekend plan","size":"heading"}]}"""
        assertEquals(ComposeErrorCode.ARTIFACT_EXISTS, refusedOf(compileText(fewer, board.sceneJson)).code)
    }

    @Test
    fun aDifferentArtifactIdIsANewArtifactBesideTheFirst() {
        val board = ComposeBoard()
        board.publishChecked(readyOf(compileText(WEEKEND_PLAN)).ops)
        val second = readyOf(compileText(WEEKEND_PLAN.replace("\"weekend-plan\"", "\"weekend-plan-2\""), board.sceneJson))
        assertFalse(second.alreadyPublished)
        assertTrue(second.ops.all { idOf(it).startsWith("cmp-weekend-plan-2-") })
        board.publishChecked(second.ops)
    }

    // ---- Placement on a board that already has things on it --------------------------------------

    @Test
    fun placementNeverOverlapsWhatIsAlreadyOnTheBoard() {
        val board = ComposeBoard().apply {
            addNote("mine", CanvasDocumentFrame(-200f, 40f, 320f, 400f))
            addBox("box", 300f, -100f, 900f, 300f)
        }
        // A legacy frameless note: placement keeps clear of where the renderer will put it too.
        board.publishChecked(listOf(CanvasOp.SetDocumentOp("", "", 0, "legacy", """{"version":2,"blocks":[]}""")))
        val before = board.sceneJson
        val occupied = CanvasComposePlacement.occupiedBounds(before)!!
        val ready = readyOf(compileText(WEEKEND_PLAN, before))
        pieceRects(ready).forEach { rect -> assertFalse(rect.intersects(occupied), "$rect overlaps $occupied") }

        // A second artifact keeps clear of the first.
        board.publishChecked(ready.ops)
        val next = readyOf(compileText(WEEKEND_PLAN.replace("\"weekend-plan\"", "\"again\""), board.sceneJson))
        val firstBounds = ready.bounds!!
        pieceRects(next).forEach { rect -> assertFalse(rect.intersects(firstBounds), "$rect overlaps $firstBounds") }
    }

    @Test
    fun anEmptyBoardStartsAtTheOrigin() {
        val ready = readyOf(compileText("""{"items":[{"kind":"NOTE","markdown":"x"}]}"""))
        val frame = (ready.ops.single() as CanvasOp.SetDocumentOp).frame!!
        assertEquals(CanvasComposePlacement.ORIGIN to CanvasComposePlacement.ORIGIN, frame.x to frame.y)
    }

    // ---- Caps -----------------------------------------------------------------------------------

    @Test
    fun twentyFourItemsCountingGroupChildrenCompileAndTwentyFiveDoNot() {
        fun request(top: Int, children: Int): String {
            val items = buildJsonArray {
                repeat(top) { add(buildJsonObject { put("kind", "NOTE"); put("markdown", "n$it") }) }
                add(
                    buildJsonObject {
                        put("kind", "GROUP")
                        put("children", buildJsonArray { repeat(children) { add(buildJsonObject { put("kind", "TEXT"); put("text", "t$it"); put("size", "body") }) } })
                    },
                )
            }
            return buildJsonObject { put("items", items) }.toString()
        }
        // 20 notes + 1 group + 3 children = 24.
        val ready = readyOf(compileText(request(20, 3)))
        assertEquals(24, ready.ops.size)
        assertIs<CanvasBatchCheck.Valid>(CanvasBatchValidator.check("", ready.ops))
        // One child more is 25.
        assertSingleProblem(refusedOf(compileText(request(20, 4))), "/items", ComposeProblemCode.TOO_MANY_ITEMS)
    }

    // ---- Colours --------------------------------------------------------------------------------

    @Test
    fun presetsMapToTheNotePaletteAndHexIsStoredLowerCase() {
        val request = """{"items":[
            {"kind":"NOTE","markdown":"a","color":"yellow"},
            {"kind":"NOTE","markdown":"b","color":"#AABBCC"},
            {"kind":"NOTE","markdown":"c"},
            {"kind":"CARD","title":"d"},
            {"kind":"CARD","title":"e","color":"purple"},
            {"kind":"CHECKLIST","items":[{"text":"f"}],"color":"red"}
        ]}"""
        val colors = readyOf(compileText(request)).ops.filterIsInstance<CanvasOp.SetDocumentOp>().map { it.color }
        assertEquals(listOf("#fde68a", "#aabbcc", null, CanvasComposeColors.CARD_DEFAULT, "#ddd6fe", "#fbcfe8"), colors)
        assertEquals(CanvasComposeContract.COLOR_PRESETS.toSet(), CanvasComposeColors.PRESETS.keys)
    }

    @Test
    fun aCardIsItsTitleFieldsAndBody() {
        val card = readyOf(compileText("""{"items":[{"kind":"CARD","title":"Walk","fields":[{"label":"When","value":"Sat"}],"markdown":"Bring **water**"}]}"""))
            .ops.single() as CanvasOp.SetDocumentOp
        assertEquals(
            CanvasCascadeBlocks.card(
                "Walk", listOf(ComposeCardField("When", "Sat")),
                listOf(MdBlock.Paragraph(MdText("Bring water", listOf(MdSpan(MdSpanStyle.BOLD, 6, 11))))),
            ),
            card.documentJson,
        )
        assertEquals("Walk", card.title)
    }

    @Test
    fun aDryRunRequestIsMarkedSo() {
        assertTrue(readyOf(compileText("""{"dry_run":true,"items":[{"kind":"NOTE","markdown":"x"}]}""")).dryRun)
        assertNull(readyOf(compileText("""{"items":[{"kind":"NOTE","markdown":"x"}]}""")).title)
    }

    // ---- Helpers --------------------------------------------------------------------------------

    /** Every problem of [refusal] is [code] at [path] (one markdown field can hold several). */
    private fun assertProblemsAt(refusal: ComposeRefusal, path: String, code: ComposeProblemCode) {
        assertTrue(refusal.problems.isNotEmpty())
        assertEquals(setOf(path to code.name), refusal.problems.map { it.path to it.code }.toSet(), "problems: ${refusal.problems}")
    }

    private fun requests(): List<Pair<String, String>> = listOf(
        "weekend plan" to WEEKEND_PLAN,
        "every kind and block" to """{"artifact_id":"all","title":"All","items":[
            {"kind":"NOTE","markdown":"# H1\n## H2\n### H3\nPara with **b**, *i*, `c`, [l](https://x.y) and ~~s~~.\n\n- a\n  - nested\n1. one\n2. two\n- [x] done\n> quote\n\n```\ncode\n```\n\n---"},
            {"kind":"CHECKLIST","title":"T","items":[{"text":"a"},{"text":"b","checked":true}],"color":"#123456"},
            {"kind":"CARD","title":"C","fields":[{"label":"L","value":""}],"markdown":"body"},
            {"kind":"TEXT","text":"Body text","size":"body"},
            {"kind":"GROUP","children":[{"kind":"TEXT","text":"in a group","size":"heading"}]}
        ]}""",
    )

    private fun describe(op: CanvasOp): String = when (op) {
        is CanvasOp.AddElementOp -> "add_element ${op.elementId}"
        is CanvasOp.SetDocumentOp -> "set_document ${op.documentId}"
        else -> op.toString()
    }

    private fun idOf(op: CanvasOp): String = when (op) {
        is CanvasOp.AddElementOp -> op.elementId
        is CanvasOp.SetDocumentOp -> op.documentId
        else -> error("unexpected $op")
    }

    private fun pieceRects(ready: ComposeCompilation.Ready): List<Slot> = ready.ops.mapNotNull { op ->
        when (op) {
            is CanvasOp.SetDocumentOp -> op.frame?.let { Slot(it.x, it.y, it.width, it.height) }
            is CanvasOp.AddElementOp -> CanvasComposePlacement.elementBounds(ComposeJson.parse(op.elementJson).jsonObject, conservative = true)
            else -> null
        }
    }

    private fun elementIds(sceneJson: String): List<String> =
        ((ComposeJson.parse(sceneJson) as JsonObject)["elements"] as JsonArray).map { it.jsonObject.string("id")!! }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
