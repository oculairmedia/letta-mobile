package com.letta.mobile.data.canvas

import com.letta.mobile.data.canvas.compose.CanvasComposePlacement
import com.letta.mobile.data.canvas.compose.Slot
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * What `canvas_get_layout` answers (letta-mobile-i9yps.3): one row per thing on the board, geometry
 * and identity only, so a large board fits in an agent's context. The same answer on the Iroh host
 * and on an app's own runtime.
 *
 * A page is at most [CanvasLayoutRead.LAYOUT_PAGE_MAX_BYTES] of JSON and [CanvasLayoutRead.MAX_LIMIT]
 * rows. [nextCursor] is absent on the last page. A cursor whose revision is not the board's current
 * revision is refused as [CanvasLayoutRefusal].
 */
@Serializable
data class CanvasLayoutResult(
    val revision: Long,
    val rows: List<CanvasLayoutRow>,
    val nextCursor: String? = null,
)

/** One thing on the board. [frame] is `[x, y, w, h]` in world units, integers, half-up. */
@Serializable
data class CanvasLayoutRow(
    val id: String,
    val kind: String,
    val frame: List<Int>? = null,
    val label: String? = null,
    /** A shape's `shapeType` (`RECTANGLE`, `ARROW`, …); absent for every other kind. */
    val shape: String? = null,
    /** A plugin element's kind (`widget` of `ext:letta.example/widget`). */
    val pluginKind: String? = null,
    /** An ARROW's ends: each a note id, an element id, or null when that end is free. */
    val bindings: CanvasLayoutBindings? = null,
)

@Serializable
data class CanvasLayoutBindings(
    val from: String? = null,
    val to: String? = null,
)

/** `{"error":"stale_cursor","revision":<current>}` — the agent calls again with no cursor. */
@Serializable
data class CanvasLayoutRefusal(
    val error: String,
    val revision: Long,
)

/** A page of layout, or a refusal the tool returns as an error. */
internal sealed interface CanvasLayoutAnswer {
    data class Page(val json: String) : CanvasLayoutAnswer
    data class Refused(val message: String) : CanvasLayoutAnswer
}

/** The scene revision a layout page and its cursor are pinned to. */
@JvmInline
internal value class LayoutRevision(val raw: Long)

/** How many rows a page may hold, already clamped to 1..[CanvasLayoutRead.MAX_LIMIT]. */
@JvmInline
internal value class PageLimit(val raw: Int)

/** The index of a row in the layout order. The cursor stores this, and the next page starts one past it. */
@JvmInline
internal value class RowIndex(val raw: Int)

/** Bytes already packed into a page, so the cap check does not re-encode every row. */
@JvmInline
internal value class PageBytes(val raw: Int)

/**
 * Builds a `canvas_get_layout` page from the scene `canvas_get_scene` would read at [revision].
 */
internal object CanvasLayoutRead {
    /** A page's JSON is never larger than this, unless a single row is (that row is a page of its own). */
    const val LAYOUT_PAGE_MAX_BYTES = 16 * 1024
    const val DEFAULT_LIMIT = 200
    const val MAX_LIMIT = 500
    const val LABEL_MAX_CHARS = 60

    const val KIND_SHAPE = "shape"
    const val KIND_TEXT = "text"
    const val KIND_PATH = "path"
    const val KIND_IMAGE = "image"
    const val KIND_NOTE = "note"
    const val KIND_PLUGIN = "plugin"

    fun answer(sceneJson: String, revision: LayoutRevision, input: JsonObject): CanvasLayoutAnswer = when (val parsed = parse(input)) {
        is ParsedRequest.Bad -> CanvasLayoutAnswer.Refused(parsed.message)
        is ParsedRequest.Ok -> page(sceneJson, revision, parsed.cursor, parsed.limit)
    }

    private fun page(sceneJson: String, revision: LayoutRevision, cursor: String?, limit: PageLimit): CanvasLayoutAnswer {
        val rows = CanvasLayoutRows.of(sceneJson)
        return when (val start = CanvasLayoutCursor.start(rows, revision, cursor)) {
            is CanvasLayoutCursor.Start.Bad -> CanvasLayoutAnswer.Refused(BAD_CURSOR)
            is CanvasLayoutCursor.Start.Stale -> stale(revision)
            is CanvasLayoutCursor.Start.At -> {
                val picked = CanvasLayoutPage.of(revision, rows, start.index, limit)
                CanvasLayoutAnswer.Page(CanvasLayoutJson.encode(picked))
            }
        }
    }

    private fun stale(revision: LayoutRevision): CanvasLayoutAnswer.Refused = CanvasLayoutAnswer.Refused(CanvasLayoutJson.stale(revision))

    private fun parse(input: JsonObject): ParsedRequest {
        val limit = limitOf(input) ?: return ParsedRequest.Bad(BAD_LIMIT)
        val cursor = cursorOf(input) ?: return ParsedRequest.Bad(BAD_CURSOR)
        return ParsedRequest.Ok(cursor.value, limit)
    }

    private fun limitOf(input: JsonObject): PageLimit? {
        val raw = input["limit"] ?: return PageLimit(DEFAULT_LIMIT)
        val primitive = raw as? JsonPrimitive ?: return null
        val number = if (primitive.isString) primitive.content.trim().toIntOrNull() else primitive.intOrNull
        if (number == null || number < 1) return null
        return PageLimit(number.coerceAtMost(MAX_LIMIT))
    }

    /** A missing or blank cursor starts at the first row; a non-string is refused. */
    private fun cursorOf(input: JsonObject): OptionalCursor? {
        val raw = input["cursor"] ?: return OptionalCursor(null)
        val primitive = raw as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return OptionalCursor(primitive.content.trim().ifEmpty { null })
    }

    private data class OptionalCursor(val value: String?)

    private sealed interface ParsedRequest {
        data class Ok(val cursor: String?, val limit: PageLimit) : ParsedRequest
        data class Bad(val message: String) : ParsedRequest
    }

    private const val BAD_LIMIT =
        "limit must be a whole number from 1 to 500 (canvas_get_layout defaults to 200 and reads anything above 500 as 500). " +
            "Example: {\"limit\":50}"

    private const val BAD_CURSOR =
        "cursor must be the nextCursor string from a previous canvas_get_layout page, or omitted to start from the first row."
}

/**
 * A printable cursor `r<revision>:<index>`: the revision the page was read at, and the index of
 * the last row on that page. The next page starts at index + 1, so two rows that share an id are
 * both returned. The agent sends it back unchanged.
 */
internal object CanvasLayoutCursor {
    private val CURSOR = Regex("""r(\d+):(\d+)""")

    fun encode(revision: LayoutRevision, index: RowIndex): String = "r${revision.raw}:${index.raw}"

    fun decode(cursor: String): Decoded? {
        val match = CURSOR.matchEntire(cursor) ?: return null
        val revision = match.groupValues[1].toLongOrNull()?.let(::LayoutRevision) ?: return null
        val index = match.groupValues[2].toIntOrNull()?.let(::RowIndex) ?: return null
        return Decoded(revision, index)
    }

    /**
     * Where the next page starts. A cursor that does not decode, or whose index is not a row with a
     * row after it (so never one [CanvasLayoutPage] minted), is [Start.Bad], not a stale revision.
     */
    fun start(rows: List<CanvasLayoutRow>, revision: LayoutRevision, cursor: String?): Start {
        if (cursor == null) return Start.At(RowIndex(0))
        val decoded = decode(cursor) ?: return Start.Bad
        if (decoded.revision != revision) return Start.Stale
        // A minted cursor always names a row before the last one; the last row has no next page.
        if (decoded.index.raw + 1 !in rows.indices) return Start.Bad
        return Start.At(RowIndex(decoded.index.raw + 1))
    }

    data class Decoded(val revision: LayoutRevision, val index: RowIndex)

    sealed interface Start {
        data class At(val index: RowIndex) : Start
        data object Bad : Start
        data object Stale : Start
    }
}

/** The rows of one page, in id order, stopping at the limit or the byte cap. */
internal object CanvasLayoutPage {
    fun of(revision: LayoutRevision, rows: List<CanvasLayoutRow>, start: RowIndex, limit: PageLimit): CanvasLayoutResult {
        val chosen = ArrayList<CanvasLayoutRow>()
        var index = start
        var packed = PageBytes(0)
        while (chosen.size < limit.raw) {
            val walk = PageWalk(revision, rows, index)
            val step = admit(walk, chosen, packed) ?: break
            chosen.add(rows[index.raw])
            packed = step.packed
            index = step.next
        }
        return CanvasLayoutResult(revision.raw, chosen, trailing(revision, rows, index, chosen))
    }

    /** The next row, or null when it does not fit or the rows have run out. */
    private fun admit(walk: PageWalk, chosen: List<CanvasLayoutRow>, packed: PageBytes): Step? {
        val row = walk.rows.getOrNull(walk.index.raw) ?: return null
        val comma = if (chosen.isEmpty()) PageBytes(0) else PageBytes(1)
        val rowSize = CanvasLayoutJson.rowBytes(row)
        val total = CanvasLayoutJson.shellBytes(walk.revision, cursorOf(walk)) + packed.raw + comma.raw + rowSize
        if (chosen.isNotEmpty() && total > CanvasLayoutRead.LAYOUT_PAGE_MAX_BYTES) return null
        return Step(PageBytes(packed.raw + comma.raw + rowSize), RowIndex(walk.index.raw + 1))
    }

    private fun cursorOf(walk: PageWalk): String? =
        if (walk.index.raw + 1 < walk.rows.size) CanvasLayoutCursor.encode(walk.revision, walk.index) else null

    private fun trailing(revision: LayoutRevision, rows: List<CanvasLayoutRow>, index: RowIndex, chosen: List<CanvasLayoutRow>): String? =
        if (index.raw < rows.size && chosen.isNotEmpty()) CanvasLayoutCursor.encode(revision, RowIndex(index.raw - 1)) else null

    private data class PageWalk(val revision: LayoutRevision, val rows: List<CanvasLayoutRow>, val index: RowIndex)

    private data class Step(val packed: PageBytes, val next: RowIndex)
}

/** Compact JSON for a page. Null fields are left out; an ARROW's binding ends are present even when null. */
internal object CanvasLayoutJson {
    fun encode(page: CanvasLayoutResult): String = buildJsonObject {
        put("revision", page.revision)
        put("rows", JsonArray(page.rows.map(::rowJson)))
        page.nextCursor?.let { put("nextCursor", it) }
    }.toString()

    fun bytes(page: CanvasLayoutResult): Int {
        val rows = page.rows.sumOf { rowBytes(it) }
        val commas = if (page.rows.size > 1) page.rows.size - 1 else 0
        return shellBytes(LayoutRevision(page.revision), page.nextCursor) + rows + commas
    }

    /** The page JSON with an empty `rows` array: the wrapper the row bytes are added to. */
    fun shellBytes(revision: LayoutRevision, cursor: String?): Int =
        encode(CanvasLayoutResult(revision.raw, emptyList(), cursor)).encodeToByteArray().size

    fun rowBytes(row: CanvasLayoutRow): Int = rowJson(row).toString().encodeToByteArray().size

    fun stale(revision: LayoutRevision): String = buildJsonObject {
        put("error", "stale_cursor")
        put("revision", revision.raw)
    }.toString()

    private fun rowJson(row: CanvasLayoutRow): JsonObject = buildJsonObject {
        put("id", row.id)
        put("kind", row.kind)
        row.frame?.let { put("frame", JsonArray(it.map(::JsonPrimitive))) }
        row.label?.let { put("label", it) }
        row.shape?.let { put("shape", it) }
        row.pluginKind?.let { put("pluginKind", it) }
        row.bindings?.let { put("bindings", bindingsJson(it)) }
    }

    private fun bindingsJson(bindings: CanvasLayoutBindings): JsonObject = buildJsonObject {
        put("from", bindings.from?.let(::JsonPrimitive) ?: JsonNull)
        put("to", bindings.to?.let(::JsonPrimitive) ?: JsonNull)
    }
}

/**
 * Every row of a scene, ordered by id then kind. Notes use their [CanvasDocumentFrame]; drawn
 * elements use the box the board already computes ([CanvasComposePlacement.elementBounds], which for
 * a rectangle, line, image or path is the bounding box of its points); plugin elements use their
 * stored frame.
 */
internal object CanvasLayoutRows {
    fun of(sceneJson: String): List<CanvasLayoutRow> {
        val arrows = CanvasOpProjector.arrowBindingsOf(sceneJson)
        val drawn = elementsOf(sceneJson).mapNotNull { elementRow(it, arrows) }
        val notes = notesOf(sceneJson)
        val plugins = CanvasOpProjector.pluginElementsOf(sceneJson).map(::pluginRow)
        return (drawn + notes + plugins).sortedWith(compareBy({ it.id }, { it.kind }))
    }

    private fun notesOf(sceneJson: String): List<CanvasLayoutRow> {
        val documents = CanvasOpProjector.documentsOf(sceneJson)
        val placed = CanvasComposePlacement.placeFrameless(documents, CanvasComposePlacement.contentBounds(sceneJson))
        return documents.map { noteRow(it, placed) }
    }

    private fun elementsOf(sceneJson: String): List<JsonObject> {
        if (sceneJson.isBlank()) return emptyList()
        val root = runCatching { canvasLayoutJson.parseToJsonElement(sceneJson) }.getOrNull() as? JsonObject ?: return emptyList()
        val elements = root["elements"] as? JsonArray ?: return emptyList()
        return elements.mapNotNull { it as? JsonObject }
    }

    private fun elementRow(element: JsonObject, arrows: Map<String, CanvasArrowBinding>): CanvasLayoutRow? {
        val id = element.string("id") ?: return null
        val kind = kindOf(element)
        return CanvasLayoutRow(
            id = id,
            kind = kind,
            frame = drawnFrame(element),
            label = elementLabel(element, kind),
            shape = element.string("shapeType")?.takeIf { kind == CanvasLayoutRead.KIND_SHAPE },
            bindings = arrowBindings(element, arrows[id]),
        )
    }

    private fun noteRow(document: CanvasSceneDocument, frameless: Map<String, Slot>): CanvasLayoutRow {
        val stored = document.frame?.let { Slot(it.x, it.y, it.width, it.height) }
        return CanvasLayoutRow(
            id = document.id,
            kind = CanvasLayoutRead.KIND_NOTE,
            frame = (stored ?: frameless[document.id])?.let(::integersOf),
            label = noteLabel(document),
        )
    }

    private fun pluginRow(element: CanvasPluginElement): CanvasLayoutRow = CanvasLayoutRow(
        id = element.id,
        kind = CanvasLayoutRead.KIND_PLUGIN,
        frame = element.frame?.let { integersOf(Slot(it.x, it.y, it.width, it.height)) },
        label = clipLabel(element.fallback.title),
        pluginKind = clipLabel(element.kind),
    )

    private fun kindOf(element: JsonObject): String = when (element.string("type")?.lowercase()) {
        "shape" -> CanvasLayoutRead.KIND_SHAPE
        "text" -> CanvasLayoutRead.KIND_TEXT
        "image" -> CanvasLayoutRead.KIND_IMAGE
        else -> CanvasLayoutRead.KIND_PATH
    }

    private fun elementLabel(element: JsonObject, kind: String): String? = when (kind) {
        CanvasLayoutRead.KIND_TEXT, CanvasLayoutRead.KIND_SHAPE -> clipLabel(element.string("text"))
        else -> null
    }

    private fun noteLabel(document: CanvasSceneDocument): String? {
        val title = clipLabel(document.title)
        if (title != null) return title
        return clipLabel(CanvasDocumentText.plainText(document.json).lineSequence().firstOrNull())
    }

    /** Document binding wins for an end; the element's startBinding/endBinding fills an end it leaves free. */
    private fun arrowBindings(element: JsonObject, document: CanvasArrowBinding?): CanvasLayoutBindings? {
        if (!element.string("shapeType").equals("ARROW", ignoreCase = true)) return null
        return CanvasLayoutBindings(
            from = document?.start?.documentId?.takeIf { it.isNotBlank() } ?: element.string("startBinding"),
            to = document?.end?.documentId?.takeIf { it.isNotBlank() } ?: element.string("endBinding"),
        )
    }

    private fun drawnFrame(element: JsonObject): List<Int> {
        val slot = CanvasComposePlacement.elementBounds(element, conservative = false)
        return integersOf(slot)
    }

    /** Edges first, then the span, so two boxes that touch stay touching after rounding. */
    private fun integersOf(box: Slot): List<Int> {
        val left = roundHalfUp(box.x)
        val top = roundHalfUp(box.y)
        val right = roundHalfUp(box.x + box.width)
        val bottom = roundHalfUp(box.y + box.height)
        return listOf(left, top, right - left, bottom - top)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private val canvasLayoutJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
}

/** Half-up, so .5 goes away from zero. World units on the wire are integers. */
internal fun roundHalfUp(value: Float): Int {
    val shifted = if (value >= 0f) value.toDouble() + 0.5 else value.toDouble() - 0.5
    return shifted.toInt()
}

/**
 * At most [CanvasLayoutRead.LABEL_MAX_CHARS] UTF-16 units, with an ellipsis when the text was
 * longer. Blank is omitted. The cut backs up one unit when it would split a surrogate pair.
 */
internal fun clipLabel(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (text.length <= CanvasLayoutRead.LABEL_MAX_CHARS) return text
    var cut = CanvasLayoutRead.LABEL_MAX_CHARS - 1
    if (cut > 0 && text[cut - 1].isHighSurrogate()) cut -= 1
    return text.take(cut) + "…"
}
