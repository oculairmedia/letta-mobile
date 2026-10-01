package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasToolContract
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * canvas.compose v1 (letta-mobile-bglj6.6, docs/design/canvas-compose-plan.md): an agent says WHAT
 * goes on the board (notes, checklists, cards, text, groups) and never WHERE; the compose layer
 * places it and compiles it into the one canvas op log. This is the wire contract only: the
 * request, the receipt and the refusal, with the caps and vocabularies both hosts hold an agent to.
 *
 * A versioned catalog of its own, deliberately not an A2UI catalog: A2UI surfaces are transient
 * interactive UI, compose artifacts are durable board content.
 */
object CanvasComposeContract {
    const val CATALOG = "letta.canvas.compose"
    const val VERSION = 1
    val SUPPORTED_VERSIONS: List<Int> = listOf(VERSION)

    /** Items in one request, the children of a GROUP counted too. */
    const val MAX_ITEMS = 24
    const val MAX_CHECKLIST_ITEMS = 40
    const val MAX_MARKDOWN_CHARS = 4_000
    const val MAX_CARD_FIELDS = 8
    const val MAX_CARD_BODY_CHARS = 500
    const val MAX_REQUEST_BYTES = 64 * 1024

    /** A GROUP holds items, never another GROUP. */
    const val MAX_GROUP_DEPTH = 1

    // Short texts: a title, label or value long enough to be a paragraph belongs in markdown.
    const val MAX_TITLE_CHARS = 120
    const val MAX_TEXT_CHARS = 500
    const val MAX_LABEL_CHARS = 60
    const val MAX_VALUE_CHARS = 200

    // Per-kind widths in world units; heights are reserved by placement, not chosen by the agent.
    const val NOTE_WIDTH = 320f
    const val CHECKLIST_WIDTH = 320f
    const val CARD_WIDTH = 320f
    const val TEXT_HEADING_WIDTH = 480f
    const val TEXT_BODY_WIDTH = 320f

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
        ComposeKind.GROUP -> error("a GROUP is as wide as its children")
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
        val ruleProblems = totalProblems(request) + duplicateKeys(request)
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

@Serializable
enum class ComposeKind { NOTE, CHECKLIST, CARD, TEXT, GROUP }

@Serializable
enum class ComposeTextSize {
    @SerialName("heading") HEADING,
    @SerialName("body") BODY,
}

/** The `canvas.compose` input. No geometry at all: explicit frames stay with `apply_ops set_document`. */
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
)

/** One thing to put on the board, told apart by its `kind`. */
@Serializable
sealed class ComposeItem {
    /** Names the produced document (`cmp-<artifactId>-<key>`); its path ordinal when absent. */
    abstract val key: String?
    abstract val kind: ComposeKind

    /** A note of markdown (the subset canvas.compose_guide lists). */
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

@Serializable
enum class ComposeStatus {
    @SerialName("published") PUBLISHED,
    @SerialName("dry_run") DRY_RUN,
}

/** World-unit rectangle of everything an artifact put on the board. */
@Serializable
data class ComposeBounds(val x: Float, val y: Float, val width: Float, val height: Float)

/** One produced item: [id] is its board id, [count] the entries of a CHECKLIST. */
@Serializable
data class ComposeReceiptItem(
    val key: String,
    val kind: ComposeKind,
    val id: String,
    val count: Int? = null,
    val children: List<ComposeReceiptItem>? = null,
)

/**
 * The `canvas.compose` return, and the source of the chat's artifact card: what was made and
 * where, without layout detail. A dry run has the same shape with [status] `dry_run`.
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
