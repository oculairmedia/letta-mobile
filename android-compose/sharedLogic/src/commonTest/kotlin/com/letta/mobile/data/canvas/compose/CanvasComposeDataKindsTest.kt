package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasOp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The v2 data kinds: TABLE compiles to a board note holding the workspace's table block; STAT,
 * PROGRESS and CHART compile to board elements inside a frame. Each is checked for what it draws,
 * the refusals a schema cannot say, the version rule, retries, and the board's own validator.
 */
class CanvasComposeDataKindsTest {
    private fun request(vararg items: String, version: Int? = null): String =
        """{${version?.let { "\"version\":$it," } ?: ""}"artifact_id":"dash","items":[${items.joinToString(",")}]}"""

    private val table = """{"kind":"TABLE","key":"t","title":"Owners","columns":["Area","Owner"],""" +
        """"rows":[["Sync","Ana"],["Search","Ben"]]}"""
    private val stat = """{"kind":"STAT","key":"s","label":"Velocity","value":"42 pts","delta":"+8%",""" +
        """"tone":"positive","caption":"vs last sprint"}"""
    private val progress = """{"kind":"PROGRESS","key":"p","label":"Release","value":0.62,"caption":"31 of 50","tone":"warning"}"""
    private val bars = """{"kind":"CHART","key":"c","title":"Closed","style":"bar",""" +
        """"points":[{"label":"W1","value":3},{"label":"W2","value":7.5}]}"""
    private val line = """{"kind":"CHART","key":"l","style":"line","unit":"°",""" +
        """"points":[{"label":"Mon","value":-2},{"label":"Tue","value":4}]}"""

    private fun problems(text: String) = refusalOf(CanvasComposeContract.decode(text)).problems.map { it.path to it.code }

    private fun elements(ready: ComposeCompilation.Ready): Map<String, JsonObject> =
        ready.ops.filterIsInstance<CanvasOp.AddElementOp>().associate { it.elementId to ComposeJson.parse(it.elementJson).jsonObject }

    // ---- The contract and its version -----------------------------------------------------------

    @Test
    fun theDataKindsAreVersionTwoAndEveryOldKindStaysVersionOne() {
        assertEquals(2, CanvasComposeContract.VERSION)
        assertEquals(listOf(1, 2), CanvasComposeContract.SUPPORTED_VERSIONS)
        assertEquals(
            listOf(ComposeKind.TABLE, ComposeKind.STAT, ComposeKind.PROGRESS, ComposeKind.CHART),
            ComposeKind.entries.filter { it.since == 2 },
        )
        assertEquals(
            setOf(ComposeKind.NOTE, ComposeKind.CHECKLIST, ComposeKind.CARD, ComposeKind.TABLE),
            ComposeKind.entries.filter { it.document }.toSet(),
        )
    }

    @Test
    fun aRequestOfDataKindsDecodesWithOrWithoutAVersion() {
        listOf(null, 2).forEach { version ->
            val decoded = requestOf(CanvasComposeContract.decode(request(table, stat, progress, bars, line, version = version)))
            assertEquals(
                listOf(ComposeKind.TABLE, ComposeKind.STAT, ComposeKind.PROGRESS, ComposeKind.CHART, ComposeKind.CHART),
                decoded.items.map { it.kind },
            )
            assertEquals(2, decoded.requiredVersion())
        }
        assertEquals(1, requestOf(CanvasComposeContract.decode(WEEKEND_PLAN)).requiredVersion())
    }

    @Test
    fun aVersionOneRequestThatUsesADataKindIsRefusedAtThatKind() {
        val group = """{"kind":"GROUP","children":[$stat]}"""
        assertEquals(
            listOf("/items/0/kind" to "BAD_VALUE", "/items/1/children/0/kind" to "BAD_VALUE"),
            problems(request(table, group, version = 1)),
        )
        assertEquals(ComposeErrorCode.UNSUPPORTED_VERSION, refusalOf(CanvasComposeContract.decode(request(table, version = 3))).code)
    }

    @Test
    fun aGroupHoldsTheDataKinds() {
        val group = """{"kind":"GROUP","label":"KPIs","children":[$stat,$progress,$table,$bars]}"""
        requestOf(CanvasComposeContract.decode(request(group)))
    }

    // ---- Refusals the schema and the rules give ---------------------------------------------------

    @Test
    fun aTableRowMustHaveOneCellPerColumn() {
        val short = """{"kind":"TABLE","columns":["A","B","C"],"rows":[["1","2","3"],["4","5"]]}"""
        assertEquals(listOf("/items/0/rows/1" to "BAD_VALUE"), problems(request(short)))
    }

    @Test
    fun theTableCapsAreHeldWithTheirPaths() {
        val columns = List(CanvasComposeContract.MAX_TABLE_COLUMNS + 1) { "\"c$it\"" }.joinToString(",")
        val wide = """{"kind":"TABLE","columns":[$columns],"rows":[["x"]]}"""
        assertEquals(listOf("/items/0/columns" to "TOO_MANY_ITEMS"), problems(request(wide)).take(1))
        val row = "[\"a\",\"b\"]"
        val rows = List(CanvasComposeContract.MAX_TABLE_ROWS + 1) { row }.joinToString(",")
        val long = """{"kind":"TABLE","columns":["A","B"],"rows":[$rows]}"""
        assertEquals(listOf("/items/0/rows" to "TOO_MANY_ITEMS"), problems(request(long)))
        val cell = "x".repeat(CanvasComposeContract.MAX_CELL_CHARS + 1)
        val longCell = """{"kind":"TABLE","columns":["A","B"],"rows":[["a","$cell"]]}"""
        assertEquals(listOf("/items/0/rows/0/1" to "TOO_LONG"), problems(request(longCell)))
        assertEquals(listOf("/items/0/rows/0/0" to "WRONG_TYPE"), problems(request("""{"kind":"TABLE","columns":["A"],"rows":[[3]]}""")))
    }

    @Test
    fun progressIsAFractionAndToneIsOneOfFour() {
        assertEquals(listOf("/items/0/value" to "BAD_VALUE"), problems(request("""{"kind":"PROGRESS","label":"x","value":1.5}""")))
        assertEquals(listOf("/items/0/value" to "BAD_VALUE"), problems(request("""{"kind":"PROGRESS","label":"x","value":-0.1}""")))
        assertEquals(listOf("/items/0/value" to "WRONG_TYPE"), problems(request("""{"kind":"PROGRESS","label":"x","value":"62%"}""")))
        assertEquals(listOf("/items/0/tone" to "BAD_VALUE"), problems(request("""{"kind":"STAT","label":"x","value":"1","tone":"good"}""")))
        assertEquals(listOf("/items/0/value" to "MISSING_FIELD"), problems(request("""{"kind":"STAT","label":"x"}""")))
    }

    @Test
    fun aChartNeedsTwoToTwelvePointsAndBarsFromZero() {
        val single = """{"kind":"CHART","style":"bar","points":[{"label":"a","value":1}]}"""
        assertEquals(listOf("/items/0/points" to "BAD_VALUE"), problems(request(single)))
        val many = List(CanvasComposeContract.MAX_CHART_POINTS + 1) { """{"label":"p$it","value":1}""" }.joinToString(",")
        val crowded = """{"kind":"CHART","style":"bar","points":[$many]}"""
        assertEquals(listOf("/items/0/points" to "TOO_MANY_ITEMS"), problems(request(crowded)))
        val negative = """{"kind":"CHART","style":"bar","points":[{"label":"a","value":1},{"label":"b","value":-1}]}"""
        assertEquals(listOf("/items/0/points/1/value" to "BAD_VALUE"), problems(request(negative)))
        assertEquals(listOf("/items/0/style" to "BAD_VALUE"), problems(request("""{"kind":"CHART","style":"pie","points":[]}""")).take(1))
        requestOf(CanvasComposeContract.decode(request(line)))
    }

    // ---- What each kind becomes -------------------------------------------------------------------

    @Test
    fun aTableIsANoteHoldingTheBoardTableBlock() {
        val ready = readyOf(compileText(request(table)))
        val document = assertIs<CanvasOp.SetDocumentOp>(ready.ops.single())
        assertEquals("cmp-dash-t", document.documentId)
        assertEquals("Owners", document.title)
        assertEquals(CanvasComposeColors.CARD_DEFAULT, document.color)
        assertEquals(CanvasComposeContract.tableWidth(2), document.frame!!.width)
        val block = ComposeJson.parse(document.documentJson).jsonObject.getValue("blocks").jsonArray.single().jsonObject
        assertEquals(CanvasCascadeBlocks.TYPE_TABLE, block.getValue("type").jsonObject.getValue("typeId").jsonPrimitive.content)
        val rows = block.getValue("content").jsonObject.getValue("data").jsonObject.getValue("rows").jsonArray
        assertEquals(
            listOf(listOf("Area", "Owner"), listOf("Sync", "Ana"), listOf("Search", "Ben")),
            rows.map { r -> r.jsonArray.map { it.jsonPrimitive.content } },
        )
        assertEquals(2, ready.items.single().count)
        assertEquals(2, document.compose!!.version)
    }

    @Test
    fun aTableGrowsWiderWithItsColumns() {
        assertEquals(320f, CanvasComposeContract.tableWidth(1))
        assertEquals(320f, CanvasComposeContract.tableWidth(2))
        assertEquals(480f, CanvasComposeContract.tableWidth(4))
        assertEquals(640f, CanvasComposeContract.tableWidth(CanvasComposeContract.MAX_TABLE_COLUMNS))
    }

    @Test
    fun aTableIsBookedTallerForEveryRowAndForWrappedCells() {
        val two = CanvasComposeReserve.reserveDocument(CanvasCascadeBlocks.table(listOf("A", "B"), listOf(listOf("1", "2"))), 320f)
        val more = CanvasComposeReserve.reserveDocument(CanvasCascadeBlocks.table(listOf("A", "B"), List(12) { listOf("1", "2") }), 320f)
        val wrapped = CanvasComposeReserve.reserveDocument(
            CanvasCascadeBlocks.table(listOf("A", "B"), List(12) { listOf("a long cell that wraps over lines", "2") }),
            320f,
        )
        assertTrue(two >= CanvasComposeReserve.MIN_RESERVE)
        assertTrue(more > two, "$more <= $two")
        assertTrue(wrapped > more, "$wrapped <= $more")
        val block = CanvasComposeReserve.blocksOf(CanvasCascadeBlocks.table(listOf("A"), listOf(listOf("1")))).single()
        assertEquals(ReserveBlockType.TABLE, block.type)
        assertEquals(listOf(listOf("A"), listOf("1")), block.cells)
    }

    @Test
    fun aStatIsAFrameWithItsTextsInside() {
        val ready = readyOf(compileText(request(stat)))
        val drawn = elements(ready)
        assertEquals(
            listOf("cmp-dash-s", "cmp-dash-s.label", "cmp-dash-s.value", "cmp-dash-s.delta", "cmp-dash-s.caption"),
            drawn.keys.toList(),
        )
        assertEquals("42 pts", drawn.getValue("cmp-dash-s.value").getValue("text").jsonPrimitive.content)
        assertEquals(32.0, drawn.getValue("cmp-dash-s.value").getValue("fontSize").jsonPrimitive.content.toDouble())
        assertEquals(CanvasComposeColors.tone(ComposeTone.POSITIVE, ""), drawn.getValue("cmp-dash-s.delta").string("strokeColor"))
        assertEquals(CanvasComposeContract.STAT_WIDTH, ready.bounds!!.width)
        assertPartsInsideTheirFrame(ready)
    }

    @Test
    fun aLongStatValueIsSetSmallerRatherThanWrapped() {
        val long = """{"kind":"STAT","key":"s","label":"Revenue","value":"€1,234,567.89"}"""
        val font = elements(readyOf(compileText(request(long)))).getValue("cmp-dash-s.value").string("fontSize").toDouble()
        assertTrue(font < STAT_VALUE_FONTS.first(), "font $font")
    }

    @Test
    fun aProgressBarIsATrackFilledToItsValue() {
        val ready = readyOf(compileText(request(progress)))
        val drawn = elements(ready)
        val track = boxOf(drawn.getValue("cmp-dash-p.track"))
        val fill = boxOf(drawn.getValue("cmp-dash-p.fill"))
        assertEquals(track.x, fill.x)
        assertEquals(kotlin.math.round(track.width * 0.62f), fill.width)
        assertEquals("62%", drawn.getValue("cmp-dash-p.percent").getValue("text").jsonPrimitive.content)
        assertEquals(CanvasComposeColors.tone(ComposeTone.WARNING, ""), drawn.getValue("cmp-dash-p.fill").string("fillColor"))
        assertPartsInsideTheirFrame(ready)
        val empty = elements(readyOf(compileText(request("""{"kind":"PROGRESS","key":"p","label":"Not started","value":0}"""))))
        assertFalse("cmp-dash-p.fill" in empty, "an empty bar has no fill")
    }

    @Test
    fun aBarChartHasABarAValueAndALabelPerPoint() {
        val ready = readyOf(compileText(request(bars)))
        val drawn = elements(ready)
        assertEquals(listOf("3", "7.5"), listOf(0, 1).map { drawn.getValue("cmp-dash-c.value$it").getValue("text").jsonPrimitive.content })
        assertEquals(listOf("W1", "W2"), listOf(0, 1).map { drawn.getValue("cmp-dash-c.label$it").getValue("text").jsonPrimitive.content })
        val (low, high) = listOf(0, 1).map { boxOf(drawn.getValue("cmp-dash-c.bar$it")) }
        assertEquals(low.bottom, high.bottom, "bars stand on one baseline")
        assertEquals(CHART_PLOT_HEIGHT, high.height, "the tallest bar fills the plot")
        assertEquals(kotlin.math.round(CHART_PLOT_HEIGHT * 3 / 7.5f), low.height)
        assertEquals(2, ready.items.single().count)
        assertPartsInsideTheirFrame(ready)
    }

    @Test
    fun aLineChartIsAPathThroughItsPointsWithZeroOnTheScale() {
        val ready = readyOf(compileText(request(line)))
        val drawn = elements(ready)
        val samples = drawn.getValue("cmp-dash-l.line").getValue("samples").jsonArray
        assertEquals(2, samples.size)
        assertEquals(listOf("-2°", "4°"), listOf(0, 1).map { drawn.getValue("cmp-dash-l.value$it").string("text") })
        assertTrue("cmp-dash-l.dot1" in drawn)
        val axis = drawn.getValue("cmp-dash-l.axis").getValue("points").jsonArray.map { it.jsonPrimitive.content.split(",")[1].toFloat() }
        val ys = samples.map { it.jsonPrimitive.content.split(",")[1].toFloat() }
        assertTrue(ys[0] > axis[0] && ys[1] < axis[0], "the zero line runs between -2 ($ys) and 4 ($axis)")
        assertPartsInsideTheirFrame(ready)
    }

    @Test
    fun valuesThatDoNotFitTheirSlotsAreShownOnceAsTheLargest() {
        val points = List(CanvasComposeContract.MAX_CHART_POINTS) { """{"label":"p$it","value":${1_000_000 + it}}""" }.joinToString(",")
        val drawn = elements(readyOf(compileText(request("""{"kind":"CHART","key":"c","style":"bar","points":[$points]}"""))))
        assertFalse(drawn.keys.any { it.startsWith("cmp-dash-c.value") })
        assertEquals("1,000,011", drawn.getValue("cmp-dash-c.range").getValue("text").jsonPrimitive.content)
    }

    @Test
    fun numbersPrintTheSameOnEveryTarget() {
        assertEquals("3", formatChartValue(3.0))
        assertEquals("7.5", formatChartValue(7.5))
        assertEquals("0.25", formatChartValue(0.25))
        assertEquals("1,234,567.89%", formatChartValue(1_234_567.891, "%"))
        assertEquals("-2", formatChartValue(-2.0))
        assertEquals("0", formatChartValue(0.0))
    }

    @Test
    fun everyDataKindPassesTheBoardsValidatorAndPlacesWithoutOverlap() {
        val group = """{"kind":"GROUP","key":"g","label":"KPIs","children":[$stat,$progress]}"""
        val board = ComposeBoard()
        val ready = readyOf(compileText(request(table, group, bars, line), board.sceneJson))
        board.publishChecked(ready.ops)
        val slots = ready.items.filter { it.kind != ComposeKind.GROUP }.map { it.frame!! }
        slots.forEachIndexed { i, a ->
            slots.drop(i + 1).forEach { b -> assertFalse(slot(a).intersects(slot(b)), "$a overlaps $b") }
        }
        assertEquals(listOf("s", "p"), ready.items.single { it.kind == ComposeKind.GROUP }.children!!.map { it.key })
    }

    @Test
    fun theSameDataKindsAgainAreARetryAndAnotherToneIsAConflict() {
        val board = ComposeBoard()
        board.publishChecked(readyOf(compileText(request(stat, bars), board.sceneJson)).ops)
        val again = readyOf(compileText(request(stat, bars), board.sceneJson))
        assertTrue(again.alreadyPublished)
        assertEquals(CanvasComposeContract.STAT_WIDTH.toInt(), again.items.first().frame!![2], "a retry reports the drawn frame")
        val otherTone = stat.replace("positive", "negative")
        assertEquals(ComposeErrorCode.ARTIFACT_EXISTS, refusedOf(compileText(request(otherTone, bars), board.sceneJson)).code)
        val otherValue = bars.replace("7.5", "8")
        assertEquals(ComposeErrorCode.ARTIFACT_EXISTS, refusedOf(compileText(request(stat, otherValue), board.sceneJson)).code)
    }

    @Test
    fun aRequestOfOldKindsKeepsVersionOneOnItsReceiptAndPieces() {
        val ready = readyOf(compileText(WEEKEND_PLAN))
        assertEquals(1, ready.receipt(com.letta.mobile.data.model.CanvasId(ComposeBoard.CANVAS), ComposeStatus.DRY_RUN, null).version)
        ready.ops.filterIsInstance<CanvasOp.SetDocumentOp>().forEach { assertEquals(1, it.compose!!.version) }
        val dataReady = readyOf(compileText(request(stat)))
        assertEquals(2, dataReady.receipt(com.letta.mobile.data.model.CanvasId(ComposeBoard.CANVAS), ComposeStatus.DRY_RUN, null).version)
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun Slot.contains(box: Slot): Boolean = box.x >= x && box.y >= y && box.right <= right && box.bottom <= bottom

    private fun slot(frame: List<Int>) = Slot(frame[0].toFloat(), frame[1].toFloat(), frame[2].toFloat(), frame[3].toFloat())

    private fun boxOf(element: JsonObject): Slot = CanvasComposePlacement.elementBounds(element, conservative = false)

    /** Every part of every drawn item lies inside that item's frame, texts at the height their wrapped lines book. */
    private fun assertPartsInsideTheirFrame(ready: ComposeCompilation.Ready) {
        val drawn = elements(ready)
        drawn.filterKeys { CanvasComposeIds.PART_SEPARATOR !in it }.forEach { (frameId, frameElement) ->
            val frame = boxOf(frameElement)
            val parts = drawn.filterKeys { it.startsWith(frameId + CanvasComposeIds.PART_SEPARATOR) }
            assertTrue(parts.isNotEmpty(), "$frameId has no parts")
            parts.forEach { (id, element) ->
                val box = CanvasComposePlacement.elementBounds(element, conservative = true)
                assertTrue(frame.contains(box), "$id $box is outside $frame")
            }
            val texts = parts.filterValues { it["type"] == JsonPrimitive("Text") }
                .mapValues { CanvasComposePlacement.elementBounds(it.value, conservative = true) }
            texts.entries.forEachIndexed { i, (a, boxA) ->
                texts.entries.drop(i + 1).forEach { (b, boxB) -> assertFalse(boxA.intersects(boxB), "$a $boxA overlaps $b $boxB") }
            }
            assertNotNull(frameElement["_compose"] as? JsonObject)
            assertEquals(2, (frameElement["_compose"] as JsonObject).getValue("version").jsonPrimitive.content.toInt())
        }
    }
}
