package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasComposeProvenance
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasGeometryOwner
import com.letta.mobile.data.canvas.CanvasOp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSceneDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * What a compile came to: a batch ready to check and publish, or why not (nothing to publish).
 */
sealed interface ComposeCompilation {
    /**
     * [ops] for the batch validator, in publish order, each made from the item at the same index of
     * [itemPaths] (its JSON pointer, `/items/3/children/0`), so a board refusal names the item. Op
     * identity (`opId`, `actorId`, `lamport`) is blank: the publisher stamps it.
     *
     * [alreadyPublished] is a retry: the artifact is on the board with this very content, [ops] is
     * empty and [bounds] is where its pieces are now.
     */
    data class Ready(
        val ops: List<CanvasOp>,
        val itemPaths: List<String>,
        val artifactId: String,
        val title: String?,
        val bounds: ComposeBounds?,
        val items: List<ComposeReceiptItem>,
        val dryRun: Boolean,
        val alreadyPublished: Boolean = false,
    ) : ComposeCompilation {
        fun receipt(canvasId: String, status: ComposeStatus, revision: Long?, warnings: List<String> = emptyList()): ComposeReceipt =
            ComposeReceipt(
                artifactId = artifactId,
                canvasId = canvasId,
                revision = revision,
                status = status,
                title = title,
                bounds = bounds,
                items = items,
                warnings = warnings,
            )
    }

    data class Refused(val refusal: ComposeRefusal) : ComposeCompilation
}

/**
 * The canvas_compose compiler (letta-mobile-bglj6.10, plan section 3.3): a request and the board it
 * lands on become one flat op batch and the receipt that describes it, or a structured refusal.
 * Pure and host-independent; [CanvasComposeService] is what the hosts call.
 *
 * In order: decode against the schema; the artifact id (the request's, else the caller's
 * fallback); item keys (`i<n>`, `i<n>-c<m>` when absent; every board id unique); content (markdown
 * to Cascade blocks per field); the idempotency check against the board; placement against the
 * board's occupied bounds with reserved heights; then ops. All or nothing: every problem found is
 * reported and no op is produced.
 *
 * The batch is flat and ordered group frames, then documents, then texts (TEXT items and group
 * labels): what is drawn under comes first. Documents are `set_document` with a frame, owner
 * AUTO, colour, title and compose provenance; TEXT and GROUP are DrawBox elements carrying their
 * provenance as `_compose`.
 */
object CanvasComposeCompiler {
    const val GROUP_Z = 0
    const val GROUP_LABEL_Z = 1
    const val TEXT_Z = 2
    const val GROUP_STROKE_WIDTH = 1.0
    const val GROUP_CORNER_RADIUS = 16.0
    const val TEXT_ALIGNMENT = "LEFT"
    const val TEXT_FONT_FAMILY = "sans"

    /** The element field that carries an element's provenance (stripped before DrawBox sees it). */
    const val ELEMENT_COMPOSE = "_compose"

    private val json = Json { ignoreUnknownKeys = true }

    /** Compiles the tool's raw input: [CanvasComposeContract.decode], then [compile]. */
    fun compile(input: JsonElement, sceneJson: String, artifactIdFallback: () -> String): ComposeCompilation =
        when (val decoded = CanvasComposeContract.decode(input)) {
            is ComposeDecoding.Refused -> ComposeCompilation.Refused(decoded.refusal)
            is ComposeDecoding.Accepted -> compile(decoded.request, sceneJson, artifactIdFallback)
        }

    /** [request] (already decoded) compiled against the board [sceneJson]. */
    fun compile(request: ComposeRequest, sceneJson: String, artifactIdFallback: () -> String): ComposeCompilation {
        val artifactId = request.artifactId ?: artifactIdFallback()
        val entries = keyed(request.items)
        keyProblems(entries, artifactId).takeIf { it.isNotEmpty() }?.let { return validation(it) }

        val problems = mutableListOf<ComposeProblem>()
        val built = entries.map { build(it, problems) }
        if (problems.isNotEmpty()) return validation(problems)

        val placement = CanvasComposePlacement.place(built.map(::sized), CanvasComposePlacement.occupiedBounds(sceneJson))
        val emitted = Emitter(artifactId, placement).emit(built)
        val receiptItems = built.map(::receiptItem)
        val dryRun = request.dryRun == true

        val existing = ExistingArtifact.of(sceneJson, artifactId)
        if (existing.isEmpty()) {
            return ComposeCompilation.Ready(emitted.map { it.op }, emitted.map { it.path }, artifactId, request.title, placement.bounds, receiptItems, dryRun)
        }
        return if (existing.signatures == emitted.associate { ExistingArtifact.idOf(it.op) to ExistingArtifact.signature(it.op) }) {
            ComposeCompilation.Ready(emptyList(), emptyList(), artifactId, request.title, existing.bounds(), receiptItems, dryRun, alreadyPublished = true)
        } else {
            ComposeCompilation.Refused(
                CanvasComposeContract.refusal(
                    ComposeErrorCode.ARTIFACT_EXISTS,
                    listOf(
                        ComposeProblem(
                            if (request.artifactId != null) "/artifact_id" else "",
                            ComposeErrorCode.ARTIFACT_EXISTS.name,
                            "artifact '$artifactId' is already on the board with other content; " +
                                "send a new artifact_id to make another (compose never changes an artifact)",
                        ),
                    ),
                ),
            )
        }
    }

    private fun validation(problems: List<ComposeProblem>) =
        ComposeCompilation.Refused(CanvasComposeContract.refusal(ComposeErrorCode.VALIDATION_FAILED, problems))

    // ---- Keys -------------------------------------------------------------------------------------

    /** An item with its pointer and its key (given, or its default). */
    private class Entry(val item: ComposeItem, val path: String, val key: String, val defaulted: Boolean, val children: List<Entry>)

    private fun keyed(items: List<ComposeItem>): List<Entry> = items.mapIndexed { i, item ->
        val path = "/items/$i"
        val children = (item as? ComposeItem.Group)?.children.orEmpty().mapIndexed { c, child ->
            Entry(child, "$path/children/$c", child.key ?: CanvasComposeIds.defaultKey(i, c), child.key == null, emptyList())
        }
        Entry(item, path, item.key ?: CanvasComposeIds.defaultKey(i), item.key == null, children)
    }

    /**
     * Every board id the request makes is unique. Given keys were held unique by the decoder; what
     * is left is a given key that takes another item's default key, or a group's label id. The
     * problem goes on the GIVEN key: that is the one the agent chose and can change.
     */
    private fun keyProblems(entries: List<Entry>, artifactId: String): List<ComposeProblem> {
        val all = entries.flatMap { listOf(it) + it.children }
        val givenAt = all.filter { !it.defaulted }.associate { it.key to it.path }
        val problems = mutableListOf<ComposeProblem>()
        all.filter { it.defaulted }.forEach { entry ->
            givenAt[entry.key]?.let { at ->
                problems += ComposeProblem(
                    "$at/key", ComposeProblemCode.DUPLICATE_KEY,
                    "key '${entry.key}' is the default key of the item at ${entry.path}; choose another",
                )
            }
        }
        entries.filter { it.item is ComposeItem.Group && !(it.item as ComposeItem.Group).label.isNullOrBlank() }.forEach { group ->
            val labelKey = group.key + CanvasComposeIds.LABEL_SUFFIX
            all.firstOrNull { it.key == labelKey }?.let { clash ->
                problems += ComposeProblem(
                    if (clash.defaulted) "${group.path}/key" else "${clash.path}/key", ComposeProblemCode.DUPLICATE_KEY,
                    "'${CanvasComposeIds.piece(artifactId, labelKey)}' is the id of the label of the group at ${group.path}; choose another key",
                )
            }
        }
        return problems
    }

    // ---- Content ----------------------------------------------------------------------------------

    /** An item with its content compiled. */
    private sealed interface Built {
        val entry: Entry

        /** A NOTE, CHECKLIST or CARD: a block document. */
        class Document(override val entry: Entry, val documentJson: String, val color: String?, val title: String?) : Built

        class Text(override val entry: Entry, val text: String, val size: ComposeTextSize) : Built

        class Group(override val entry: Entry, val label: String?, val children: List<Built>) : Built
    }

    private fun build(entry: Entry, problems: MutableList<ComposeProblem>): Built = when (val item = entry.item) {
        is ComposeItem.Note -> Built.Document(
            entry,
            blocks(item.markdown, "${entry.path}/markdown", problems)?.let(CanvasCascadeBlocks::document).orEmpty(),
            CanvasComposeColors.of(item.color),
            item.title,
        )
        is ComposeItem.Checklist -> Built.Document(entry, CanvasCascadeBlocks.checklist(item.items), CanvasComposeColors.of(item.color), item.title)
        is ComposeItem.Card -> {
            val body = item.markdown?.takeIf { it.isNotBlank() }?.let { blocks(it, "${entry.path}/markdown", problems) }.orEmpty()
            Built.Document(
                entry,
                CanvasCascadeBlocks.card(item.title, item.fields.orEmpty(), body),
                CanvasComposeColors.of(item.color, CanvasComposeColors.CARD_DEFAULT),
                item.title,
            )
        }
        is ComposeItem.Text -> Built.Text(entry, item.text, item.size)
        is ComposeItem.Group -> Built.Group(entry, item.label?.takeIf { it.isNotBlank() }, entry.children.map { build(it, problems) })
    }

    private fun blocks(markdown: String, path: String, problems: MutableList<ComposeProblem>): List<MdBlock>? =
        when (val parsed = CanvasComposeMarkdown.parse(markdown, path)) {
            is MdParse.Parsed -> parsed.blocks
            is MdParse.Refused -> null.also { problems += parsed.problems }
        }

    // ---- Placement and the receipt ---------------------------------------------------------------

    private fun sized(built: Built): SizedItem = when (built) {
        is Built.Group -> SizedItem.Group(built.entry.key, built.label, built.children.map { sized(it) as SizedItem.Leaf })
        else -> leaf(built)
    }

    private fun leaf(built: Built): SizedItem.Leaf = when (built) {
        is Built.Document -> {
            val width = CanvasComposeContract.width(built.entry.item.kind)
            SizedItem.Leaf(built.entry.key, width, CanvasComposeReserve.reserveDocument(built.documentJson, width))
        }
        is Built.Text -> SizedItem.Leaf(
            built.entry.key,
            CanvasComposeContract.width(ComposeKind.TEXT, built.size),
            CanvasComposeReserve.reserveText(built.text, built.size),
        )
        is Built.Group -> error("a group is not a leaf")
    }

    /** Without its board id: that is `cmp-<artifactId>-<key>`, derived by a reader (ComposeReceiptItem.boardId). */
    private fun receiptItem(built: Built): ComposeReceiptItem = ComposeReceiptItem(
        key = built.entry.key,
        kind = built.entry.item.kind,
        count = (built.entry.item as? ComposeItem.Checklist)?.items?.size,
        children = (built as? Built.Group)?.children?.map(::receiptItem),
    )

    // ---- Ops --------------------------------------------------------------------------------------

    private class Emitted(val op: CanvasOp, val path: String)

    private class Emitter(private val artifactId: String, private val placement: Placement) {
        fun emit(built: List<Built>): List<Emitted> {
            val all = built.flatMap { if (it is Built.Group) listOf(it) + it.children else listOf(it) }
            val groups = all.filterIsInstance<Built.Group>().map { Emitted(groupFrame(it), it.entry.path) }
            val documents = all.filterIsInstance<Built.Document>().map { Emitted(document(it), it.entry.path) }
            val texts = all.mapNotNull { piece ->
                when (piece) {
                    is Built.Text -> Emitted(text(piece), piece.entry.path)
                    is Built.Group -> piece.label?.let { Emitted(label(piece, it), piece.entry.path) }
                    is Built.Document -> null
                }
            }
            return groups + documents + texts
        }

        private fun slot(entry: Entry): Slot = placement.slots.getValue(entry.key)

        private fun provenance(entry: Entry) = CanvasComposeProvenance(
            artifactId = artifactId,
            key = entry.key,
            kind = entry.item.kind.name,
            catalog = CanvasComposeContract.CATALOG,
            version = CanvasComposeContract.VERSION,
        )

        private fun document(piece: Built.Document): CanvasOp.SetDocumentOp {
            val slot = slot(piece.entry)
            return CanvasOp.SetDocumentOp(
                opId = "",
                actorId = "",
                lamport = 0L,
                documentId = CanvasComposeIds.piece(artifactId, piece.entry.key),
                documentJson = piece.documentJson,
                frame = CanvasDocumentFrame(slot.x, slot.y, slot.width, slot.height),
                color = piece.color,
                title = piece.title,
                owner = CanvasGeometryOwner.AUTO,
                compose = provenance(piece.entry),
            )
        }

        private fun text(piece: Built.Text): CanvasOp.AddElementOp {
            val slot = slot(piece.entry)
            val font = CanvasComposeReserve.textFont(piece.size)
            return element(
                CanvasComposeIds.piece(artifactId, piece.entry.key),
                textJson(piece.text, slot, font, CanvasComposeColors.TEXT, TEXT_Z, piece.entry),
            )
        }

        private fun label(group: Built.Group, label: String): CanvasOp.AddElementOp {
            val slot = placement.labels.getValue(group.entry.key)
            return element(
                CanvasComposeIds.label(artifactId, group.entry.key),
                textJson(label, slot, CanvasComposePlacement.GROUP_LABEL_FONT.toDouble(), CanvasComposeColors.GROUP_LABEL, GROUP_LABEL_Z, group.entry),
            )
        }

        private fun groupFrame(group: Built.Group): CanvasOp.AddElementOp {
            val slot = slot(group.entry)
            return element(
                CanvasComposeIds.piece(artifactId, group.entry.key),
                buildJsonObject {
                    put("type", "Shape")
                    put("shapeType", "RECTANGLE")
                    put("points", buildJsonArray { add(JsonPrimitive(point(slot.x, slot.y))); add(JsonPrimitive(point(slot.right, slot.bottom))) })
                    put("strokeColor", CanvasComposeColors.GROUP_STROKE)
                    put("strokeWidth", GROUP_STROKE_WIDTH)
                    put("fillColor", CanvasComposeColors.GROUP_FILL)
                    put("cornerRadius", GROUP_CORNER_RADIUS)
                    put("zIndex", GROUP_Z)
                    put(ELEMENT_COMPOSE, provenanceJson(group.entry))
                },
            )
        }

        private fun textJson(text: String, slot: Slot, font: Double, color: String, z: Int, entry: Entry): JsonObject = buildJsonObject {
            put("type", "Text")
            put("text", text)
            put("textTopLeft", point(slot.x, slot.y))
            put("wrapWidth", slot.width.toDouble())
            put("fontSize", font)
            put("alignment", TEXT_ALIGNMENT)
            put("fontFamilyKey", TEXT_FONT_FAMILY)
            put("strokeColor", color)
            put("zIndex", z)
            put(ELEMENT_COMPOSE, provenanceJson(entry))
        }

        private fun provenanceJson(entry: Entry): JsonElement =
            json.encodeToJsonElement(CanvasComposeProvenance.serializer(), provenance(entry))

        private fun element(id: String, body: JsonObject) =
            CanvasOp.AddElementOp(opId = "", actorId = "", lamport = 0L, elementId = id, elementJson = body.toString())
    }

    /**
     * DrawBox's `"x,y"`, written the same on every target: placement hands out whole numbers, and
     * a whole number is printed with one decimal as the JVM does (Kotlin/JS would drop it).
     */
    internal fun point(x: Float, y: Float): String = "${number(x)},${number(y)}"

    private fun number(value: Float): String {
        val whole = value.toLong()
        return if (whole.toFloat() == value) "$whole.0" else value.toString()
    }

    // ---- Idempotency ------------------------------------------------------------------------------

    /**
     * The pieces of an artifact already on the board, by board id, each as its content without
     * geometry: a person may have moved a note since, and that does not make a retry a conflict.
     */
    private class ExistingArtifact(
        val signatures: Map<String, JsonObject>,
        private val documents: List<CanvasSceneDocument>,
        private val elements: List<JsonObject>,
    ) {
        fun isEmpty(): Boolean = signatures.isEmpty()

        /** Where the pieces are now: document frames, group frames and TEXT items as compose books them. */
        fun bounds(): ComposeBounds? {
            val rects = documents.mapNotNull { document -> document.frame?.let { Slot(it.x, it.y, it.width, it.height) } } +
                elements.mapNotNull { element ->
                    when (kindOf(element)) {
                        ComposeKind.GROUP.name -> if (element.string("type") == "Shape") CanvasComposePlacement.elementBounds(element, conservative = false) else null
                        ComposeKind.TEXT.name -> textSlot(element)
                        else -> null
                    }
                }
            return CanvasComposePlacement.union(rects)
        }

        private fun textSlot(element: JsonObject): Slot? {
            val (x, y) = element.string("textTopLeft")?.split(",")?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 } ?: return null
            val size = if ((element.number("fontSize") ?: 0.0) >= CanvasComposeReserve.TEXT_HEADING_FONT) ComposeTextSize.HEADING else ComposeTextSize.BODY
            val width = element.number("wrapWidth")?.toFloat() ?: CanvasComposeContract.width(ComposeKind.TEXT, size)
            return Slot(x, y, width, CanvasComposeReserve.reserveText(element.string("text").orEmpty(), size, width))
        }

        companion object {
            /** What an element's content is made of; position and DrawBox defaults are not content. */
            private val ELEMENT_CONTENT = listOf("type", "shapeType", "text", "fontSize", ELEMENT_COMPOSE)

            fun of(sceneJson: String, artifactId: String): ExistingArtifact {
                val documents = CanvasOpProjector.documentsOf(sceneJson).filter { it.compose?.artifactId == artifactId }
                val root = runCatching { json.parseToJsonElement(sceneJson) }.getOrNull() as? JsonObject
                val elements = (root?.get("elements") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .filter { artifactOf(it) == artifactId }
                val signatures = documents.associate { it.id to documentSignature(it.json, it.color, it.title, it.compose) } +
                    elements.mapNotNull { element -> element.string("id")?.let { it to elementSignature(element) } }
                return ExistingArtifact(signatures, documents, elements)
            }

            fun idOf(op: CanvasOp): String = when (op) {
                is CanvasOp.SetDocumentOp -> op.documentId
                is CanvasOp.AddElementOp -> op.elementId
                else -> error("compose emits no ${op::class.simpleName}")
            }

            fun signature(op: CanvasOp): JsonObject = when (op) {
                is CanvasOp.SetDocumentOp -> documentSignature(op.documentJson, op.color, op.title, op.compose)
                is CanvasOp.AddElementOp -> elementSignature(json.parseToJsonElement(op.elementJson) as JsonObject)
                else -> error("compose emits no ${op::class.simpleName}")
            }

            private fun documentSignature(documentJson: String, color: String?, title: String?, compose: CanvasComposeProvenance?) = buildJsonObject {
                put("document", runCatching { json.parseToJsonElement(documentJson) }.getOrElse { JsonPrimitive(documentJson) })
                put("color", color?.lowercase())
                put("title", title?.takeIf { it.isNotBlank() })
                put("compose", compose?.let { json.encodeToJsonElement(CanvasComposeProvenance.serializer(), it) } ?: JsonNull)
            }

            private fun elementSignature(element: JsonObject) = buildJsonObject {
                ELEMENT_CONTENT.forEach { key ->
                    val value = element[key] ?: return@forEach
                    val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                    put(key, if (number != null) JsonPrimitive(number) else value)
                }
            }

            private fun composeOf(element: JsonObject): JsonObject? = element[ELEMENT_COMPOSE] as? JsonObject

            private fun artifactOf(element: JsonObject): String? = composeOf(element)?.string("artifactId")

            private fun kindOf(element: JsonObject): String? = composeOf(element)?.string("kind")

            private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

            private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        }
    }
}
