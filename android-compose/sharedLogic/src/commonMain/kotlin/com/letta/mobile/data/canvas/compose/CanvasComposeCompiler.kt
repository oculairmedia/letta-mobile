package com.letta.mobile.data.canvas.compose

import com.letta.mobile.data.canvas.CanvasOp
import kotlinx.serialization.json.JsonElement

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
        val emitted = ComposeEmitter(artifactId, placement).emit(built)
        val ready = ComposeCompilation.Ready(
            ops = emitted.map { it.op },
            itemPaths = emitted.map { it.path },
            artifactId = artifactId,
            title = request.title,
            bounds = placement.bounds,
            items = built.map(::receiptItem),
            dryRun = request.dryRun == true,
        )
        val existing = ExistingArtifact.of(sceneJson, artifactId)
        return when {
            existing.isEmpty() -> ready
            existing.matches(emitted) -> ready.copy(ops = emptyList(), itemPaths = emptyList(), bounds = existing.bounds(), alreadyPublished = true)
            else -> artifactExists(request, artifactId)
        }
    }

    /** The artifact is on the board with other content: compose never changes an artifact. */
    private fun artifactExists(request: ComposeRequest, artifactId: String): ComposeCompilation.Refused {
        val problem = ComposeProblem(
            if (request.artifactId != null) "/artifact_id" else "",
            ComposeErrorCode.ARTIFACT_EXISTS.name,
            "artifact '$artifactId' is already on the board with other content; " +
                "send a new artifact_id to make another (compose never changes an artifact)",
        )
        return ComposeCompilation.Refused(CanvasComposeContract.refusal(ComposeErrorCode.ARTIFACT_EXISTS, listOf(problem)))
    }

    private fun validation(problems: List<ComposeProblem>) =
        ComposeCompilation.Refused(CanvasComposeContract.refusal(ComposeErrorCode.VALIDATION_FAILED, problems))

    // ---- Keys -------------------------------------------------------------------------------------

    /** An item with its pointer and its key (given, or its default). */
    internal class Entry(val item: ComposeItem, val path: String, val key: String, val defaulted: Boolean, val children: List<Entry>)

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
    internal sealed interface Built {
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
}
