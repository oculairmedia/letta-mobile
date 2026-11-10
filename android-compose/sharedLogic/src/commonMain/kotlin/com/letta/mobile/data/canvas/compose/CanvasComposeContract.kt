package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.model.CanvasArtifactId
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canvas_compose v1 (docs/design/canvas-compose-plan.md): an agent says WHAT
 * goes on the board (notes, checklists, cards, text, groups) and never WHERE; the compose layer
 * places it and compiles it into the one canvas op log. This is the wire contract only: the
 * request, the receipt and the refusal, with the caps and vocabularies both hosts hold an agent to.
 *
 * A versioned catalog of its own, deliberately not an A2UI catalog: A2UI surfaces are transient
 * interactive UI, compose artifacts are durable board content.
 */
object CanvasComposeContract {
    const val CATALOG = "letta.canvas.compose"

    /**
     * The wire version this build speaks. Changes inside v1 were additive and both ways compatible:
     * - receipt items no longer carry `id` (derivable as
     *   `cmp-<artifact_id>-<key>`); a reader still takes one when present, as in receipts already
     *   stored in conversations.
     *
     * Version 2 adds the data kinds TABLE, STAT, PROGRESS and CHART ([ComposeKind.since]). Every
     * v1 request is still accepted and compiles to exactly what it did; a request that says
     * `"version": 1` and uses a v2 kind is refused at that item's `kind`. A host built before v2
     * refuses `"version": 2` as UNSUPPORTED_VERSION and a v2 kind as UNKNOWN_KIND, both structured.
     */
    const val VERSION = 2
    val SUPPORTED_VERSIONS: List<Int> = listOf(1, VERSION)

    /** Items in one request, the children of a GROUP counted too. */
    const val MAX_ITEMS = 24
    const val MAX_CHECKLIST_ITEMS = 40
    const val MAX_MARKDOWN_CHARS = 4_000
    const val MAX_CARD_FIELDS = 8
    const val MAX_CARD_BODY_CHARS = 500
    const val MAX_REQUEST_BYTES = 64 * 1024

    /**
     * The receipt's budget: `message.list` ships a tool return over 4 096 bytes as a 2 KiB preview
     * (`MessageListWireProjection.TOOL_RETURN_PROJECTION_THRESHOLD_BYTES`, held equal by
     * CanvasComposeReceiptSizeTest), and a previewed receipt has no bounds for the chat card to
     * frame. The largest receipt the caps allow stays under it.
     */
    const val MAX_RECEIPT_BYTES = 4_096

    /** A GROUP holds items, never another GROUP. */
    const val MAX_GROUP_DEPTH = 1

    // Short texts: a title, label or value long enough to be a paragraph belongs in markdown.
    const val MAX_TITLE_CHARS = 120
    const val MAX_TEXT_CHARS = 500
    const val MAX_LABEL_CHARS = 60
    const val MAX_VALUE_CHARS = 200

    // TABLE: a header row of column names over rows of short plain-text cells.
    const val MAX_TABLE_COLUMNS = 6
    const val MAX_TABLE_ROWS = 20
    const val MAX_CELL_CHARS = 60

    // STAT: a short label over one prominent value, with an optional delta and caption.
    const val MAX_STAT_VALUE_CHARS = 24
    const val MAX_STAT_NOTE_CHARS = 80

    // CHART: one labelled series of numbers.
    const val MAX_CHART_POINTS = 12
    const val MIN_CHART_POINTS = 2
    const val MAX_CHART_LABEL_CHARS = 16
    const val MAX_CHART_UNIT_CHARS = 8

    /** A chart value's magnitude; larger numbers belong in a unit ("k", "M"), not in 16 digits. */
    const val MAX_CHART_VALUE = 1_000_000_000_000.0

    // Per-kind widths in world units; heights are reserved by placement, not chosen by the agent.
    const val NOTE_WIDTH = 320f
    const val CHECKLIST_WIDTH = 320f
    const val CARD_WIDTH = 320f
    const val TEXT_HEADING_WIDTH = 480f
    const val TEXT_BODY_WIDTH = 320f
    const val STAT_WIDTH = 240f
    const val PROGRESS_WIDTH = 320f
    const val CHART_WIDTH = 480f
    const val TABLE_MEDIUM_WIDTH = 480f
    const val TABLE_WIDE_WIDTH = 640f

    /** A TABLE's width grows with its columns, so cells stay readable: 320, 480 or 640. */
    fun tableWidth(columns: Int): Float = when {
        columns <= 2 -> NOTE_WIDTH
        columns <= 4 -> TABLE_MEDIUM_WIDTH
        else -> TABLE_WIDE_WIDTH
    }

    const val KEY_PATTERN = "^[a-z0-9][a-z0-9_-]{0,31}$"
    const val ARTIFACT_ID_PATTERN = "^[a-z0-9][a-z0-9_-]{0,47}$"

    /** The JSON Canvas preset colour names; anything else is `#rrggbb`. */
    val COLOR_PRESETS: List<String> = listOf("red", "orange", "yellow", "green", "cyan", "purple")
    val COLOR_PATTERN: String = "^(" + COLOR_PRESETS.joinToString("|") + "|#[0-9a-fA-F]{6})$"

    private val keyRegex = Regex(KEY_PATTERN)
    private val artifactIdRegex = Regex(ARTIFACT_ID_PATTERN)
    private val colorRegex = Regex(COLOR_PATTERN)

    fun isKey(value: String): Boolean = keyRegex.matches(value)

    fun isArtifactId(value: String): Boolean = artifactIdRegex.matches(value)

    fun isColor(value: String): Boolean = colorRegex.matches(value)

    /** The width every item of [kind] is placed at ([size] matters only for TEXT). */
    fun width(kind: ComposeKind, size: ComposeTextSize? = null): Float = when (kind) {
        ComposeKind.NOTE -> NOTE_WIDTH
        ComposeKind.CHECKLIST -> CHECKLIST_WIDTH
        ComposeKind.CARD -> CARD_WIDTH
        ComposeKind.TEXT -> if (size == ComposeTextSize.HEADING) TEXT_HEADING_WIDTH else TEXT_BODY_WIDTH
        ComposeKind.STAT -> STAT_WIDTH
        ComposeKind.PROGRESS -> PROGRESS_WIDTH
        ComposeKind.CHART -> CHART_WIDTH
        ComposeKind.TABLE -> error("a TABLE is as wide as its columns; use tableWidth")
        ComposeKind.GROUP -> error("a GROUP is as wide as its children")
    }

    /** The width [item] is placed at: its kind's, a TABLE's by its columns, a TEXT's by its size. */
    fun widthOf(item: ComposeItem): Float = when (item) {
        is ComposeItem.Table -> tableWidth(item.columns.size)
        is ComposeItem.Text -> width(ComposeKind.TEXT, item.size)
        else -> width(item.kind)
    }

    /** What every refusal ends with: nothing happened, and where the format is described. */
    const val REFUSAL_HINT: String = "Nothing was published. Fix every problem and send the whole request again; " +
        "${CanvasToolContract.COMPOSE_GUIDE} describes the format."

    /**
     * The wire codec. Strict on the way in (a field the contract does not name is an error, which
     * [CanvasComposeSchema] reports with its path before this ever sees it) and without nulls on
     * the way out, so an absent optional field stays absent.
     */
    val json: Json = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    /**
     * A request as the tool receives it, checked against the schema the model was given, then
     * the rules a schema cannot say (the item total across groups, unique keys), then decoded.
     * Never throws: anything wrong is a [ComposeRefusal] whose problems carry JSON-pointer paths.
     */
    fun decode(input: JsonElement): ComposeDecoding {
        val size = input.toString().encodeToByteArray().size
        if (size > MAX_REQUEST_BYTES) {
            return refused(ComposeProblem("", ComposeProblemCode.TOO_LONG, "the request is $size bytes; at most $MAX_REQUEST_BYTES"))
        }
        if (input !is JsonObject) return refused(ComposeProblem("", ComposeProblemCode.WRONG_TYPE, "the request must be a JSON object"))
        unsupported(input)?.let { return ComposeDecoding.Refused(it) }
        val shapeProblems = CanvasComposeSchema.check(input)
        if (shapeProblems.isNotEmpty()) return ComposeDecoding.Refused(refusal(ComposeErrorCode.VALIDATION_FAILED, shapeProblems))
        val request = try {
            json.decodeFromJsonElement(ComposeRequest.serializer(), input)
        } catch (e: IllegalArgumentException) {
            // A SerializationException too. The schema and the DTOs agree (CanvasComposeSchemaTest),
            // so reaching this is a contract bug, still answered as a refusal rather than a crash.
            return refused(ComposeProblem("", ComposeProblemCode.WRONG_TYPE, "the request does not decode: ${e.message}"))
        }
        val ruleProblems = totalProblems(request) + duplicateKeys(request) + itemRuleProblems(request)
        return if (ruleProblems.isEmpty()) {
            ComposeDecoding.Accepted(request)
        } else {
            ComposeDecoding.Refused(refusal(ComposeErrorCode.VALIDATION_FAILED, ruleProblems))
        }
    }

    /** [decode] of request text; text that is not JSON is refused like any other bad request. */
    fun decode(text: String): ComposeDecoding {
        val size = text.encodeToByteArray().size
        if (size > MAX_REQUEST_BYTES) {
            return refused(ComposeProblem("", ComposeProblemCode.TOO_LONG, "the request is $size bytes; at most $MAX_REQUEST_BYTES"))
        }
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return refused(ComposeProblem("", ComposeProblemCode.WRONG_TYPE, "the request is not JSON: ${e.message}"))
        }
        return decode(element)
    }

    fun refusal(code: ComposeErrorCode, problems: List<ComposeProblem>): ComposeRefusal =
        ComposeRefusal(code = code, problems = problems, hint = REFUSAL_HINT)

    private fun refused(problem: ComposeProblem) =
        ComposeDecoding.Refused(refusal(ComposeErrorCode.VALIDATION_FAILED, listOf(problem)))

    /** Another catalog or version is not a malformed request but one this board does not speak. */
    private fun unsupported(input: JsonObject): ComposeRefusal? {
        val problems = buildList {
            val catalog = input["catalog"]
            if (catalog != null && catalog.stringOrNull() != CATALOG) {
                add(ComposeProblem("/catalog", ComposeProblemCode.BAD_VALUE, "catalog $catalog is not supported; use \"$CATALOG\""))
            }
            val version = input["version"]
            if (version != null && version.intOrNull() !in SUPPORTED_VERSIONS) {
                add(
                    ComposeProblem(
                        "/version", ComposeProblemCode.BAD_VALUE,
                        "version $version is not supported; supported: ${SUPPORTED_VERSIONS.joinToString()}",
                    ),
                )
            }
        }
        return if (problems.isEmpty()) null else refusal(ComposeErrorCode.UNSUPPORTED_VERSION, problems)
    }

    private fun totalProblems(request: ComposeRequest): List<ComposeProblem> {
        val total = request.items.sumOf { 1 + ((it as? ComposeItem.Group)?.children?.size ?: 0) }
        if (total <= MAX_ITEMS) return emptyList()
        return listOf(
            ComposeProblem(
                "/items", ComposeProblemCode.TOO_MANY_ITEMS,
                "at most $MAX_ITEMS items in one request, the children of groups counted (got $total)",
            ),
        )
    }

    /** Keys name the produced documents, so they are unique across the whole request; the first one wins. */
    private fun duplicateKeys(request: ComposeRequest): List<ComposeProblem> {
        val seen = mutableMapOf<String, String>()
        val problems = mutableListOf<ComposeProblem>()
        fun visit(item: ComposeItem, path: String) {
            item.key?.let { key ->
                val first = seen[key]
                if (first == null) {
                    seen[key] = path
                } else {
                    problems += ComposeProblem("$path/key", ComposeProblemCode.DUPLICATE_KEY, "key '$key' is already used at $first")
                }
            }
            if (item is ComposeItem.Group) item.children.forEachIndexed { i, child -> visit(child, "$path/children/$i") }
        }
        request.items.forEachIndexed { i, item -> visit(item, "/items/$i") }
        return problems
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.intOrNull(): Int? =
        (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
}

/** A request read: either one to compile, or why not. */
sealed interface ComposeDecoding {
    data class Accepted(val request: ComposeRequest) : ComposeDecoding

    data class Refused(val refusal: ComposeRefusal) : ComposeDecoding
}

/**
 * What an item is. [since] is the catalog version that introduced it; [document] is true for the
 * kinds compiled to a board note (a block document the renderer fits to its content), false for
 * the ones drawn as board elements.
 */
@Serializable
enum class ComposeKind(val since: Int = 1, val document: Boolean = false) {
    NOTE(document = true),
    CHECKLIST(document = true),
    CARD(document = true),
    TEXT,
    GROUP,
    TABLE(since = 2, document = true),
    STAT(since = 2),
    PROGRESS(since = 2),
    CHART(since = 2),
}

/** How a STAT's delta or a PROGRESS bar reads: neutral, good, bad, or needing attention. */
@Serializable
enum class ComposeTone {
    @SerialName("neutral") NEUTRAL,
    @SerialName("positive") POSITIVE,
    @SerialName("negative") NEGATIVE,
    @SerialName("warning") WARNING,
}

/** How a CHART draws its series: a bar per point, or a line through them. */
@Serializable
enum class ComposeChartStyle {
    @SerialName("bar") BAR,
    @SerialName("line") LINE,
}

@Serializable
enum class ComposeTextSize {
    @SerialName("heading") HEADING,
    @SerialName("body") BODY,
}

/** The `canvas_compose` input. No geometry at all: explicit frames stay with `apply_ops set_document`. */
@Serializable
data class ComposeRequest(
    val catalog: String? = null,
    val version: Int? = null,
    @SerialName("canvas_id") val canvasId: String? = null,
    /** The idempotency key: the same id with the same content is a retry, with other content a conflict. */
    @SerialName("artifact_id") val artifactId: String? = null,
    val title: String? = null,
    val items: List<ComposeItem>,
    @SerialName("dry_run") val dryRun: Boolean? = null,
) {
    /**
     * The catalog version this request needs: the one it names, else the newest [ComposeKind.since]
     * of its items, so a request of v1 kinds is answered as v1 exactly as before.
     */
    fun requiredVersion(): Int = version ?: allItems().maxOfOrNull { it.kind.since } ?: 1

    /** Every item, the children of groups after the top level. */
    fun allItems(): List<ComposeItem> = items + items.flatMap { (it as? ComposeItem.Group)?.children.orEmpty() }
}

/** One thing to put on the board, told apart by its `kind`. */
@Serializable
sealed class ComposeItem {
    /** Names the produced document (`cmp-<artifactId>-<key>`); its path ordinal when absent. */
    abstract val key: String?
    abstract val kind: ComposeKind

    /** A note of markdown (the subset canvas_compose_guide lists). */
    @Serializable
    @SerialName("NOTE")
    data class Note(
        override val key: String? = null,
        val title: String? = null,
        val markdown: String,
        val color: String? = null,
    ) : ComposeItem() {
        override val kind: ComposeKind get() = ComposeKind.NOTE
    }

    /** A note of to-dos; each entry becomes a checkable line. */
    @Serializable
    @SerialName("CHECKLIST")
    data class Checklist(
        override val key: String? = null,
        val title: String? = null,
        val items: List<ComposeChecklistItem>,
        val color: String? = null,
    ) : ComposeItem() {
        override val kind: ComposeKind get() = ComposeKind.CHECKLIST
    }

    /** A titled note with label/value fields and an optional short body; never a container. */
    @Serializable
    @SerialName("CARD")
    data class Card(
        override val key: String? = null,
        val title: String,
        val fields: List<ComposeCardField>? = null,
        val markdown: String? = null,
        val color: String? = null,
    ) : ComposeItem() {
        override val kind: ComposeKind get() = ComposeKind.CARD
    }

    /** Free text on the board itself, not in a note. */
    @Serializable
    @SerialName("TEXT")
    data class Text(
        override val key: String? = null,
        val text: String,
        val size: ComposeTextSize,
    ) : ComposeItem() {
        override val kind: ComposeKind get() = ComposeKind.TEXT
    }

    /** A titled table: a header row of [columns] over [rows], each row one plain-text cell per column. */
    @Serializable
    @SerialName("TABLE")
    data class Table(
        override val key: String? = null,
        val title: String? = null,
        val columns: List<String>,
        val rows: List<List<String>>,
        val color: String? = null,
    ) : ComposeItem() {
        override val kind: ComposeKind get() = ComposeKind.TABLE
    }

    /** A dashboard figure: a [label], a prominent [value], and an optional [delta] in its [tone] and [caption]. */
    @Serializable
    @SerialName("STAT")
    data class Stat(
        override val key: String? = null,
        val label: String,
        val value: String,
        val delta: String? = null,
        val tone: ComposeTone? = null,
        val caption: String? = null,
    ) : ComposeItem() {
        override val kind: ComposeKind get() = ComposeKind.STAT
    }

    /** A labelled bar filled to [value] (0 to 1) in its [tone], with an optional [caption]. */
    @Serializable
    @SerialName("PROGRESS")
    data class Progress(
        override val key: String? = null,
        val label: String,
        val value: Double,
        val caption: String? = null,
        val tone: ComposeTone? = null,
    ) : ComposeItem() {
        override val kind: ComposeKind get() = ComposeKind.PROGRESS
    }

    /** One labelled series of numbers drawn as bars or a line, with an optional [title] and [unit]. */
    @Serializable
    @SerialName("CHART")
    data class Chart(
        override val key: String? = null,
        val title: String? = null,
        val style: ComposeChartStyle,
        val points: List<ComposeChartPoint>,
        val unit: String? = null,
    ) : ComposeItem() {
        override val kind: ComposeKind get() = ComposeKind.CHART
    }

    /** A labelled frame around other items; the only container, one level deep. */
    @Serializable
    @SerialName("GROUP")
    data class Group(
        override val key: String? = null,
        val label: String? = null,
        val children: List<ComposeItem>,
    ) : ComposeItem() {
        override val kind: ComposeKind get() = ComposeKind.GROUP
    }
}

@Serializable
data class ComposeChecklistItem(val text: String, val checked: Boolean? = null)

@Serializable
data class ComposeCardField(val label: String, val value: String)

/** One point of a CHART: its category [label] and its [value]. */
@Serializable
data class ComposeChartPoint(val label: String, val value: Double)

@Serializable
enum class ComposeStatus {
    @SerialName("published") PUBLISHED,
    @SerialName("dry_run") DRY_RUN,
}

/** World-unit rectangle of everything an artifact put on the board. */
@Serializable
data class ComposeBounds(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * One produced item: its [key] and [kind], [count] the entries of a CHECKLIST, the rows of a TABLE
 * or the points of a CHART.
 *
 * Its board id is derived, `cmp-<artifact_id>-<key>` ([boardId]), so the receipt does not carry
 * it: with it, the largest receipt the caps allow was over the 4 KiB above
 * which `message.list` ships a tool return as a 2 KiB preview, and lost its bounds on reload.
 * [id] is still read when present, as in receipts written before the slimming; nothing writes it.
 */
@Serializable
data class ComposeReceiptItem(
    val key: String,
    val kind: ComposeKind,
    val id: String? = null,
    val count: Int? = null,
    /**
     * `[x, y, w, h]` in world units, integers, half-up. Absent when the receipt had to drop it
     * ([ComposeReceipt.framesOmitted] is then true), or, on a retry, when the board no longer stores
     * this piece's frame ([ComposeReceipt.framesOmitted] stays null).
     */
    val frame: List<Int>? = null,
    val children: List<ComposeReceiptItem>? = null,
) {
    /** The board id of this piece of [artifactId]: the one the receipt names, else the derived one. */
    fun boardId(artifactId: CanvasArtifactId): String = id ?: CanvasComposeIds.piece(artifactId, key)

    /** [boardId] of this piece of [receipt]'s artifact. */
    fun boardIdIn(receipt: ComposeReceipt): String = boardId(CanvasArtifactId(receipt.artifactId))
}

/**
 * The `canvas_compose` return, and the source of the chat's artifact card: what was made and
 * where, without layout detail. A dry run has the same shape with [status] `dry_run`.
 *
 * Kept under [MAX_RECEIPT_BYTES] at every cap (no per-item board ids; see [ComposeReceiptItem]),
 * so a reload reads it whole and the card can still frame [bounds].
 */
@Serializable
data class ComposeReceipt(
    val ok: Boolean = true,
    val catalog: String = CanvasComposeContract.CATALOG,
    val version: Int = CanvasComposeContract.VERSION,
    @SerialName("artifact_id") val artifactId: String,
    @SerialName("canvas_id") val canvasId: String,
    val revision: Long? = null,
    val status: ComposeStatus,
    val title: String? = null,
    val bounds: ComposeBounds? = null,
    val items: List<ComposeReceiptItem>,
    val warnings: List<String> = emptyList(),
    /** True when any item's frame was left off so the receipt stayed within [MAX_RECEIPT_BYTES]. */
    val framesOmitted: Boolean? = null,
    /** Set with [framesOmitted]: where to read the geometry that did not fit. */
    val framesHint: String? = null,
)

@Serializable
enum class ComposeErrorCode {
    VALIDATION_FAILED,
    UNSUPPORTED_VERSION,
    ARTIFACT_EXISTS,
    BOARD_REFUSED,
    UNAUTHORIZED,
    CANVAS_NOT_FOUND,
}

/**
 * Why one part of a request is wrong. A BOARD_REFUSED problem carries the board's own invariant
 * (`element.exists`, `document.size`, ...) as its code instead, so the board's rules keep one
 * vocabulary; that is why [ComposeProblem.code] is a string.
 */
enum class ComposeProblemCode {
    UNKNOWN_KIND,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    TOO_MANY_ITEMS,
    TOO_LONG,
    DUPLICATE_KEY,
    BAD_KEY,
    BAD_COLOR,
    UNSUPPORTED_MARKDOWN,
    NESTING_TOO_DEEP,
    WRONG_TYPE,
    BAD_VALUE,
}

/** One problem: [path] is a JSON pointer into the request (`/items/2/markdown`). */
@Serializable
data class ComposeProblem(val path: String, val code: String, val message: String) {
    constructor(path: String, code: ComposeProblemCode, message: String) : this(path, code.name, message)
}

/** Nothing was published, and why, as `ExternalToolResult.Error` content. */
@Serializable
data class ComposeRefusal(
    val ok: Boolean = false,
    val code: ComposeErrorCode,
    val catalog: String = CanvasComposeContract.CATALOG,
    val version: Int = CanvasComposeContract.VERSION,
    val problems: List<ComposeProblem>,
    val hint: String,
)
