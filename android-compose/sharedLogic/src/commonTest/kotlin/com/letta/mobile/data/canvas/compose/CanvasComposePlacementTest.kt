package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSceneDocument
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic placement (letta-mobile-bglj6.9, plan 3.4): golden layouts, groups, where an
 * artifact goes relative to what is on the board, and the properties placement promises (same
 * input same frames, no two slots overlap, nothing lands on existing content).
 */
class CanvasComposePlacementTest {
    private fun leaf(key: String, height: Float = 200f, width: Float = 320f) = SizedItem.Leaf(key, width, height)

    private fun leaves(n: Int, height: (Int) -> Float = { 200f }) = List(n) { leaf("i$it", height(it)) }

    @Test
    fun oneItemOnAnEmptyBoardSitsAtTheOrigin() {
        val placement = CanvasComposePlacement.place(listOf(leaf("only", 150f)), contentBounds = null)
        assertEquals(mapOf("only" to Slot(80f, 80f, 320f, 150f)), placement.slots)
        assertEquals(ComposeBounds(80f, 80f, 320f, 150f), placement.bounds)
    }

    @Test
    fun twoItemsShareOneRow() {
        val placement = CanvasComposePlacement.place(listOf(leaf("a", 120f), leaf("b", 300f)), null)
        assertEquals(listOf(Slot(80f, 80f, 320f, 120f), Slot(424f, 80f, 320f, 300f)), placement.slots.values.toList())
        assertEquals(ComposeBounds(80f, 80f, 664f, 300f), placement.bounds)
    }

    /**
     * Five items, three columns:
     * ```
     *   x=80        x=424       x=768
     *   +---------+ +---------+ +---------+   y=80
     *   |   i0    | |   i1    | |   i2    |   h=200
     *   +---------+ +---------+ +---------+
     *   +---------+ +---------+               y=304 (80 + 200 + 24)
     *   |   i3    | |   i4    |
     *   +---------+ +---------+
     * ```
     */
    @Test
    fun fiveItemsFlowIntoThreeColumns() {
        val placement = CanvasComposePlacement.place(leaves(5), null)
        assertEquals(
            listOf(
                Slot(80f, 80f, 320f, 200f), Slot(424f, 80f, 320f, 200f), Slot(768f, 80f, 320f, 200f),
                Slot(80f, 304f, 320f, 200f), Slot(424f, 304f, 320f, 200f),
            ),
            placement.slots.values.toList(),
        )
        assertEquals(ComposeBounds(80f, 80f, 1008f, 424f), placement.bounds)
    }

    @Test
    fun tenItemsFlowIntoFourColumns() {
        val placement = CanvasComposePlacement.place(leaves(10), null)
        val slots = placement.slots.values.toList()
        assertEquals(listOf(80f, 424f, 768f, 1112f, 80f, 424f, 768f, 1112f, 80f, 424f), slots.map { it.x })
        assertEquals(listOf(80f, 80f, 80f, 80f, 304f, 304f, 304f, 304f, 528f, 528f), slots.map { it.y })
        assertEquals(ComposeBounds(80f, 80f, 1352f, 648f), placement.bounds)
    }

    /**
     * Twenty-four items, four columns, six rows; i5 is 300 tall, so row 2 is too:
     * ```
     *   x=80     x=424    x=768    x=1112
     *   [ i0 ]   [ i1 ]   [ i2 ]   [ i3 ]    y=80    row h 160
     *   [ i4 ]   [ i5 ]   [ i6 ]   [ i7 ]    y=264   row h 300 (i5)
     *            [    ]
     *   [ i8 ]   [ i9 ]   [i10 ]   [i11 ]    y=588
     *   [i12 ]   [i13 ]   [i14 ]   [i15 ]    y=772
     *   [i16 ]   [i17 ]   [i18 ]   [i19 ]    y=956
     *   [i20 ]   [i21 ]   [i22 ]   [i23 ]    y=1140  (bottom 1300)
     * ```
     */
    @Test
    fun twentyFourItemsFlowIntoFourColumnsAndRowsTakeTheirTallestItem() {
        val placement = CanvasComposePlacement.place(leaves(24) { if (it == 5) 300f else 160f }, null)
        val slots = placement.slots.values.toList()
        val columns = listOf(80f, 424f, 768f, 1112f)
        val rows = listOf(80f, 264f, 588f, 772f, 956f, 1140f)
        slots.forEachIndexed { i, slot ->
            assertEquals(columns[i % 4], slot.x, "x of i$i")
            assertEquals(rows[i / 4], slot.y, "y of i$i")
        }
        assertEquals(300f, slots[5].height)
        assertEquals(ComposeBounds(80f, 80f, 1352f, 1220f), placement.bounds)
    }

    @Test
    fun columnsFollowTheItemCount() {
        assertEquals(listOf(1, 2, 2, 2, 3, 3, 3, 3, 3, 4, 4), (1..11).map { CanvasComposePlacement.columns(it) })
        assertEquals(4, CanvasComposePlacement.columns(24))
    }

    @Test
    fun theColumnIsAsWideAsTheWidestItem() {
        val placement = CanvasComposePlacement.place(listOf(leaf("heading", 63f, 480f), leaf("note")), null)
        assertEquals(Slot(80f, 80f, 480f, 63f), placement.slots["heading"])
        assertEquals(Slot(584f, 80f, 320f, 200f), placement.slots["note"])
    }

    /**
     * The request fixture's shape: a heading, a checklist, a note and a group of two.
     * ```
     *   x=80                          x=816
     *   [ heading 480x63 ]            [ shopping 320x200 ]          y=80
     *   [ meals 320x300  ]            +--- selfcare 712x260 ----+   y=304
     *   [                ]            | Self-care               |   label y=320
     *   [                ]            | [ walk ]   [ read ]     |   y=360
     *                                 +-------------------------+
     * ```
     */
    @Test
    fun aGroupFramesItsChildrenAndTakesOneCell() {
        val placement = CanvasComposePlacement.place(GROUP_CASE, null)
        assertEquals(GROUP_CASE_SLOTS, placement.slots)
        assertEquals(mapOf("selfcare" to Slot(840f, 320f, 664f, 32f)), placement.labels)
        assertEquals(ComposeBounds(80f, 80f, 1448f, 524f), placement.bounds)
        val frame = placement.slots.getValue("selfcare")
        listOf("walk", "read").forEach { assertTrue(frame.contains(placement.slots.getValue(it)), it) }
        assertTrue(frame.contains(placement.labels.getValue("selfcare")))
    }

    @Test
    fun aLongGroupLabelGetsATallerRowAndPushesTheChildrenDown() {
        val label = "A group label that is long enough to wrap onto a second line"
        val placement = CanvasComposePlacement.place(listOf(SizedItem.Group("g", label, listOf(leaf("c")))), null)
        val row = placement.labels.getValue("g").height
        assertTrue(row > CanvasComposePlacement.GROUP_LABEL_ROW, "$row")
        assertEquals(80f + 24f + row, placement.slots.getValue("c").y)
        assertTrue(placement.labels.getValue("g").bottom <= placement.slots.getValue("c").y)
    }

    @Test
    fun anUnlabelledOrEmptyGroupStillGetsAFrame() {
        val placement = CanvasComposePlacement.place(listOf(SizedItem.Group("g", null, emptyList())), null)
        assertEquals(mapOf("g" to Slot(80f, 80f, 368f, 80f)), placement.slots)
        assertTrue(placement.labels.isEmpty())
    }

    @Test
    fun anArtifactGoesRightOfNarrowContentTopAligned() {
        val content = ComposeBounds(-100f, 50f, 600f, 900f)
        val placement = CanvasComposePlacement.place(leaves(2), content)
        assertEquals(Slot(548f, 50f, 320f, 200f), placement.slots["i0"])
    }

    @Test
    fun anArtifactGoesBelowWideContentLeftAligned() {
        val content = ComposeBounds(-100f, 50f, 2_401f, 900f)
        val placement = CanvasComposePlacement.place(leaves(2), content)
        assertEquals(Slot(-100f, 998f, 320f, 200f), placement.slots["i0"])
        // 2 400 exactly is not wider than 2 400: still to the right.
        val edge = CanvasComposePlacement.place(leaves(1), ComposeBounds(0f, 0f, 2_400f, 10f))
        assertEquals(Slot(2_448f, 0f, 320f, 200f), edge.slots["i0"])
    }

    @Test
    fun nothingToPlaceIsAnEmptyPlacement() {
        val placement = CanvasComposePlacement.place(emptyList(), null)
        assertTrue(placement.slots.isEmpty())
        assertNull(placement.bounds)
    }

    @Test
    fun theSameInputPlacesTheSameWay() {
        val random = Random(41)
        repeat(300) {
            val items = randomItems(random)
            val content = randomBounds(random)
            assertEquals(CanvasComposePlacement.place(items, content), CanvasComposePlacement.place(items.toList(), content?.copy()))
        }
    }

    @Test
    fun noTwoSlotsOverlapAndNoneLandsOnTheContent() {
        val random = Random(43)
        repeat(1_000) {
            val items = randomItems(random)
            val content = randomBounds(random)
            val placement = CanvasComposePlacement.place(items, content)
            assertNoOverlaps(items, placement)
            items.forEach { item ->
                val slot = placement.slots.getValue(item.key)
                if (content != null) assertFalse(slot.intersects(content), "${item.key} $slot on $content")
                assertTrue(placement.bounds!!.containsSlot(slot))
            }
        }
    }

    @Test
    fun twoArtifactsComposedOneAfterTheOtherDoNotOverlap() {
        val random = Random(47)
        repeat(200) {
            var scene = sceneJson(elements = if (random.nextBoolean()) listOf(rectangle(0f, 0f, random.nextInt(10, 3_000).toFloat(), 400f)) else emptyList())
            val placed = mutableListOf<Slot>()
            repeat(2) { round ->
                val items = randomItems(random).map {
                    when (it) {
                        is SizedItem.Leaf -> it.copy(key = "r$round-${it.key}")
                        is SizedItem.Group -> it.renamed("r$round-")
                    }
                }
                val placement = CanvasComposePlacement.place(items, CanvasComposePlacement.occupiedBounds(scene))
                val leaves = leafSlots(items, placement)
                leaves.forEach { slot -> placed.forEach { other -> assertFalse(slot.intersects(other), "$slot on $other") } }
                placed += leaves
                // The compiled documents land on the board with their frames, as the compiler writes them.
                scene = sceneJson(
                    elements = elementsOf(scene),
                    documents = documentsOf(scene) + leaves.mapIndexed { i, slot -> "r$round-doc$i" to slot },
                )
            }
        }
    }

    @Test
    fun framelessDocumentsAreLaidOutInIdOrderWithoutOverlapAndFramedOnesAreLeftAlone() {
        val framed = CanvasSceneDocument("a-framed", NOTE_JSON, CanvasDocumentFrame(0f, 0f, 300f, 200f))
        val documents = listOf(
            CanvasSceneDocument("n3", NOTE_JSON),
            framed,
            CanvasSceneDocument("n1", CanvasComposeFixtures.document(CanvasComposeFixtures.B("paragraph", "x ".repeat(400)))),
            CanvasSceneDocument("n2", NOTE_JSON),
        )
        val content = ComposeBounds(0f, 0f, 300f, 200f)
        val slots = CanvasComposePlacement.placeFrameless(documents, content)
        assertEquals(listOf("n1", "n2", "n3"), slots.keys.toList())
        slots.values.forEach { slot ->
            assertEquals(320f, slot.width)
            assertFalse(slot.intersects(content))
        }
        assertEquals(Slot(348f, 0f, 320f, CanvasComposeReserve.reserveDocument(documents[2].json)), slots["n1"])
        assertNoPairOverlaps(slots.values.toList())
    }

    @Test
    fun framelessDocumentsNeverOverlap() {
        val random = Random(53)
        repeat(300) {
            val documents = List(random.nextInt(0, 30)) { i ->
                val text = "word ".repeat(random.nextInt(0, 300))
                val frame = if (random.nextInt(4) == 0) CanvasDocumentFrame(random.nextInt(-500, 500).toFloat(), 0f, 320f, 240f) else null
                CanvasSceneDocument("d$i", CanvasComposeFixtures.document(CanvasComposeFixtures.B("paragraph", text)), frame)
            }
            val content = randomBounds(random)
            val slots = CanvasComposePlacement.placeFrameless(documents, content)
            assertEquals(documents.filter { it.frame == null }.map { it.id }.sorted(), slots.keys.toList())
            assertNoPairOverlaps(slots.values.toList())
            if (content != null) slots.values.forEach { assertFalse(it.intersects(content)) }
        }
    }

    @Test
    fun contentBoundsAgreeWithTheRenderersZoomToFit() {
        // The same scene and numbers are in sharedUI CanvasViewportFitTest, which decodes it with DrawBox
        // and runs CanvasViewportFit.contentBounds: the two must stay equal.
        assertEquals(ComposeBounds(-20f, -80f, 1020f, 880f), CanvasComposePlacement.contentBounds(SHARED_FIXTURE_SCENE))
    }

    @Test
    fun contentBoundsOfNothingAreNull() {
        assertNull(CanvasComposePlacement.contentBounds(""))
        assertNull(CanvasComposePlacement.contentBounds("not json"))
        assertNull(CanvasComposePlacement.contentBounds("""{"bgColor":"#ffffffff","elements":[]}"""))
        assertNull(CanvasComposePlacement.occupiedBounds("""{"bgColor":"#ffffffff","elements":[]}"""))
    }

    @Test
    fun occupiedBoundsCoverRotatedElementsWrappedTextAndFramelessNotes() {
        val content = CanvasComposePlacement.contentBounds(SHARED_FIXTURE_SCENE)!!
        val occupied = CanvasComposePlacement.occupiedBounds(SHARED_FIXTURE_SCENE)!!
        assertTrue(occupied.containsBounds(content), "$occupied does not cover $content")
        // The frameless note sits right of the content, so the occupied area reaches past it.
        assertTrue(occupied.x + occupied.width > content.x + content.width)

        val rotated = sceneJson(elements = listOf(rectangle(0f, 0f, 100f, 10f, rotation = 90f)))
        assertEquals(ComposeBounds(0f, 0f, 100f, 10f), CanvasComposePlacement.contentBounds(rotated))
        val turned = CanvasComposePlacement.occupiedBounds(rotated)!!
        assertEquals(45f, turned.x, 0.01f)
        assertEquals(-45f, turned.y, 0.01f)
        assertEquals(100f, turned.height, 0.01f)

        val text = sceneJson(
            elements = listOf(
                buildJsonObject {
                    put("id", "t"); put("type", "Text"); put("zIndex", 0); put("points", JsonArray(emptyList()))
                    put("strokeColor", "#000000ff"); put("strokeWidth", 1f)
                    put("text", "a long text element that wraps over several lines of its box")
                    put("textTopLeft", "0.0,0.0"); put("wrapWidth", 100f); put("fontSize", 20f)
                },
            ),
        )
        assertEquals(24f, CanvasComposePlacement.contentBounds(text)!!.height)
        assertTrue(CanvasComposePlacement.occupiedBounds(text)!!.height > 100f)
    }

    private fun assertNoOverlaps(items: List<SizedItem>, placement: Placement) {
        // Outer cells, pairwise.
        assertNoPairOverlaps(items.map { placement.slots.getValue(it.key) })
        items.filterIsInstance<SizedItem.Group>().forEach { group ->
            val frame = placement.slots.getValue(group.key)
            val children = group.children.map { placement.slots.getValue(it.key) }
            assertNoPairOverlaps(children)
            children.forEach { assertTrue(frame.contains(it), "${group.key}: $it outside $frame") }
            placement.labels[group.key]?.let { label ->
                assertTrue(frame.contains(label))
                children.forEach { assertFalse(label.intersects(it), "label $label on child $it") }
            }
        }
    }

    private fun assertNoPairOverlaps(slots: List<Slot>) {
        slots.forEachIndexed { i, a ->
            slots.drop(i + 1).forEach { b -> assertFalse(a.intersects(b), "$a overlaps $b") }
        }
    }

    private fun leafSlots(items: List<SizedItem>, placement: Placement): List<Slot> = items.flatMap { item ->
        when (item) {
            is SizedItem.Leaf -> listOf(placement.slots.getValue(item.key))
            // The group's frame is on the board as a rectangle; its children sit inside it.
            is SizedItem.Group -> listOf(placement.slots.getValue(item.key))
        }
    }

    private fun SizedItem.Group.renamed(prefix: String) =
        copy(key = prefix + key, children = children.map { it.copy(key = prefix + it.key) })

    private fun Slot.contains(other: Slot) = other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom

    private fun ComposeBounds.containsSlot(slot: Slot) = Slot(x, y, width, height).contains(slot)

    private fun ComposeBounds.containsBounds(other: ComposeBounds) = containsSlot(Slot(other.x, other.y, other.width, other.height))

    internal companion object {
        val NOTE_JSON = CanvasComposeFixtures.document(CanvasComposeFixtures.B("paragraph", "A note"))

        val GROUP_CASE: List<SizedItem> = listOf(
            SizedItem.Leaf("heading", 480f, 63f),
            SizedItem.Leaf("shopping", 320f, 200f),
            SizedItem.Leaf("meals", 320f, 300f),
            SizedItem.Group("selfcare", "Self-care", listOf(SizedItem.Leaf("walk", 320f, 180f), SizedItem.Leaf("read", 320f, 120f))),
        )

        val GROUP_CASE_SLOTS: Map<String, Slot> = linkedMapOf(
            "heading" to Slot(80f, 80f, 480f, 63f),
            "shopping" to Slot(816f, 80f, 320f, 200f),
            "meals" to Slot(80f, 304f, 320f, 300f),
            "selfcare" to Slot(816f, 304f, 712f, 260f),
            "walk" to Slot(840f, 360f, 320f, 180f),
            "read" to Slot(1184f, 360f, 320f, 120f),
        )

        /**
         * A scene with one element of every geometry DrawBox knows, a framed note and a frameless
         * one. Copied verbatim into sharedUI CanvasViewportFitTest; content bounds (-20, -80) to
         * (1000, 800).
         */
        const val SHARED_FIXTURE_SCENE: String = """{"bgColor":"#ffffffff","elements":[""" +
            """{"id":"path","type":"Path","zIndex":0,"points":[],"strokeColor":"#000000ff","strokeWidth":2.0,"samples":["-20.0,10.0,2.0","40.0,30.0,2.0"]},""" +
            """{"id":"rect","type":"Shape","zIndex":1,"points":["0.0,200.0","50.0,220.0","120.0,260.0"],"strokeColor":"#000000ff","strokeWidth":1.0,"shapeType":"RECTANGLE"},""" +
            """{"id":"circle","type":"Shape","zIndex":2,"points":["100.0,100.0","160.0,180.0"],"strokeColor":"#000000ff","strokeWidth":1.0,"shapeType":"CIRCLE"},""" +
            """{"id":"arrow","type":"Shape","zIndex":3,"points":["300.0,0.0","500.0,0.0"],"strokeColor":"#000000ff","strokeWidth":1.0,"shapeType":"ARROW","bend":"0.0,-80.0"},""" +
            """{"id":"text","type":"Text","zIndex":4,"points":[],"strokeColor":"#000000ff","strokeWidth":1.0,"text":"Hello","textTopLeft":"600.0,50.0","wrapWidth":200.0,"fontSize":20.0},""" +
            """{"id":"image","type":"Image","zIndex":5,"points":["900.0,400.0","1000.0,500.0"],"strokeColor":"#000000ff","strokeWidth":1.0,"intrinsicWidth":100.0,"intrinsicHeight":100.0}""" +
            """],"_documents":[""" +
            """{"id":"framed","json":"{\"version\":2,\"blocks\":[]}","frame":{"x":500.0,"y":600.0,"width":300.0,"height":200.0}},""" +
            """{"id":"frameless","json":"{\"version\":2,\"blocks\":[]}"}""" +
            """]}"""

        fun rectangle(x: Float, y: Float, width: Float, height: Float, rotation: Float = 0f): JsonObject = buildJsonObject {
            put("id", "rect-$x-$y-$width")
            put("type", "Shape")
            put("zIndex", 0)
            put("points", buildJsonArray { add(JsonPrimitive("$x,$y")); add(JsonPrimitive("${x + width},${y + height}")) })
            put("strokeColor", "#000000ff")
            put("strokeWidth", 1f)
            put("shapeType", "RECTANGLE")
            if (rotation != 0f) put("rotation", rotation)
        }

        fun sceneJson(elements: List<JsonObject> = emptyList(), documents: List<Pair<String, Slot>> = emptyList()): String = buildJsonObject {
            put("bgColor", "#ffffffff")
            put("elements", JsonArray(elements))
            put(
                "_documents",
                buildJsonArray {
                    documents.forEach { (id, slot) ->
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("json", NOTE_JSON)
                                put(
                                    "frame",
                                    buildJsonObject {
                                        put("x", slot.x); put("y", slot.y); put("width", slot.width); put("height", slot.height)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }.toString()

        fun elementsOf(scene: String): List<JsonObject> =
            (ComposeJson.parse(scene) as JsonObject)["elements"]!!.let { it as JsonArray }.map { it as JsonObject }

        fun documentsOf(scene: String): List<Pair<String, Slot>> =
            com.letta.mobile.data.canvas.CanvasOpProjector.documentsOf(scene).map { d ->
                val f = d.frame!!
                d.id to Slot(f.x, f.y, f.width, f.height)
            }

        fun randomItems(random: Random): List<SizedItem> = RandomItems(random).make()

        fun randomBounds(random: Random): ComposeBounds? = if (random.nextInt(4) == 0) {
            null
        } else {
            ComposeBounds(
                random.nextInt(-3_000, 3_000).toFloat(),
                random.nextInt(-3_000, 3_000).toFloat(),
                random.nextInt(0, 5_000).toFloat(),
                random.nextInt(0, 5_000).toFloat(),
            )
        }
    }
}

/** Random items for the placement properties, keyed `k0`, `k1`, ... in the order they are made. */
private class RandomItems(private val random: Random) {
    private var n = 0

    fun make(): List<SizedItem> {
        var budget = random.nextInt(1, CanvasComposeContract.MAX_ITEMS + 1)
        val items = mutableListOf<SizedItem>()
        while (budget > 0) {
            val width = if (random.nextInt(5) == 0) 480f else 320f
            val height = random.nextInt(32, 1_201).toFloat()
            val item = if (budget >= 2 && random.nextInt(4) == 0) group(budget) else SizedItem.Leaf("k${n++}", width, height)
            items += item
            budget -= if (item is SizedItem.Group) 1 + item.children.size else 1
        }
        return items
    }

    /** A group of at most [budget] - 1 children (nine at most), labelled or not. */
    private fun group(budget: Int): SizedItem.Group {
        val children = List(random.nextInt(1, minOf(budget - 1, 9) + 1)) { child() }
        val label = if (random.nextBoolean()) "label ".repeat(random.nextInt(1, 12)) else null
        return SizedItem.Group("k${n++}", label, children)
    }

    private fun child() = SizedItem.Leaf("k${n++}", if (random.nextBoolean()) 320f else 480f, random.nextInt(32, 1_201).toFloat())
}
