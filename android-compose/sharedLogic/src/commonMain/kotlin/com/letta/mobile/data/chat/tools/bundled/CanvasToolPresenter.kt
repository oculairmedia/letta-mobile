package com.letta.mobile.data.chat.tools.bundled

import com.letta.mobile.data.canvas.names.CanvasToolNames
import com.letta.mobile.data.chat.tools.GenericToolPresenter
import com.letta.mobile.data.chat.tools.ToolArguments
import com.letta.mobile.data.chat.tools.ToolCallFacts
import com.letta.mobile.data.chat.tools.ToolFailure
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolMatcher
import com.letta.mobile.data.chat.tools.ToolPresenter
import com.letta.mobile.data.chat.tools.ToolPresenterScope
import com.letta.mobile.data.chat.tools.ToolPreviewTarget
import com.letta.mobile.data.chat.tools.ToolStepLine
import com.letta.mobile.data.chat.tools.ToolVerb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The canvas tools, whichever way the agent reached them: a native `canvas_*` call, or the canvas
 * CLI run from a shell tool (`meridian canvas compose …`) or the `meridian` meta-tool, which the
 * timeline already reads as the native call it runs (MeridianCommandCall). Each reads as the
 * canvas op it ran ("Composed canvas · Sprint plan", "Edited canvas · add_element ×2, connect",
 * "Read canvas · layout"), opens to a small preview of the op (the composed items, the ops in
 * order), and its chip opens the canvas.
 */
object CanvasToolPresenter : ToolPresenter {
    override val id: String = "canvas"

    override val matcher: ToolMatcher = ToolMatcher(CanvasToolNames.ALL)

    override fun line(call: ToolCallFacts, scope: ToolPresenterScope): ToolStepLine {
        val name = call.name.lowercase()
        return ToolStepLine(
            verb = VERBS[name] ?: ToolVerb.ReadCanvas,
            target = targetOf(call, name),
            previews = listOfNotNull(ToolPreviewTarget.Canvas.takeIf { name !in NO_CANVAS }),
            failure = call.canvasFailure(),
            presenterId = id,
        )
    }

    override fun inset(call: ToolCallFacts, scope: ToolPresenterScope): ToolInset? {
        val preview = when (call.name.lowercase()) {
            CanvasToolNames.COMPOSE -> call.array(ITEMS)?.let(::composedItems)
            CanvasToolNames.APPLY_OPS -> call.array(OPS)?.let(::appliedOps)
            else -> null
        }
        return preview?.let(ToolInset::Text) ?: GenericToolPresenter.argumentsInset(call)
    }

    /** What the line names: a compose's title, an ops batch's op types, a new canvas's title, else the CLI's name for the op. */
    private fun targetOf(call: ToolCallFacts, name: String): String = when (name) {
        CanvasToolNames.COMPOSE -> call.string(TITLE) ?: call.array(ITEMS)?.firstOrNull()?.let(::itemTitle).orEmpty()
        CanvasToolNames.APPLY_OPS -> call.array(OPS)?.let(::opSummary).orEmpty()
        CanvasToolNames.CREATE -> call.string(TITLE).orEmpty()
        else -> CLI_OPS[name].orEmpty()
    }.let(ToolArguments::firstLine)
}

/** `NOTE  Risks`, one line per composed item (a group's children indented under it), capped. */
private fun composedItems(items: JsonArray): String = items.itemLines().capped()

/** These items' lines, each group's children after it, one indent deeper. */
private fun JsonArray.itemLines(): List<String> = filterIsInstance<JsonObject>().flatMap { item ->
    val line = listOfNotNull(item.text(KIND), itemTitle(item)).joinToString("  ")
    listOf(line) + (item[CHILDREN] as? JsonArray)?.itemLines().orEmpty().map { INDENT + it }
}

/** An item's own words: its title, else its text, label or first line of markdown. */
private fun itemTitle(item: JsonElement): String? =
    (item as? JsonObject)?.let { obj -> ITEM_TITLE_KEYS.firstNotNullOfOrNull(obj::text)?.let(ToolArguments::firstLine) }

/** `add_element  box-1`, one line per op in order, capped. */
private fun appliedOps(ops: JsonArray): String =
    ops.filterIsInstance<JsonObject>()
        .map { op -> listOfNotNull(op.text(TYPE), OP_ID_KEYS.firstNotNullOfOrNull(op::text)).joinToString("  ") }
        .capped()

/** The op types in order of first use, each with its count: `add_element ×2, connect`. */
private fun opSummary(ops: JsonArray): String =
    ops.mapNotNull { (it as? JsonObject)?.text(TYPE) }
        .groupingBy { it }.eachCount()
        .entries.joinToString(", ") { (type, count) -> if (count == 1) type else "$type ×$count" }

private fun List<String>.capped(): String =
    if (size <= PREVIEW_LINES) joinToString("\n") else (take(PREVIEW_LINES) + "… +${size - PREVIEW_LINES}").joinToString("\n")

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun ToolCallFacts.array(key: String): JsonArray? = arguments?.get(key) as? JsonArray

/**
 * A canvas tool refused or failed: its result is `{"ok":false, …}` or `{"error": …}`. Only the
 * result's start is read.
 */
private fun ToolCallFacts.canvasFailure(): ToolFailure? {
    val head = result?.trimStart()?.take(FAILURE_SCAN_CHARS) ?: return null
    val failed = head.startsWith("{\"error\"") || "\"ok\":false" in head.replace(" ", "")
    return ToolFailure(null).takeIf { failed }
}

private val VERBS: Map<String, ToolVerb> = mapOf(
    CanvasToolNames.COMPOSE to ToolVerb.ComposedCanvas,
    CanvasToolNames.APPLY_OPS to ToolVerb.EditedCanvas,
    CanvasToolNames.REPLACE_SCENE to ToolVerb.EditedCanvas,
    CanvasToolNames.CREATE to ToolVerb.CreatedCanvas,
)

/** The canvas CLI's name for each read-only op (`meridian canvas layout`), what its line names. */
private val CLI_OPS: Map<String, String> = mapOf(
    CanvasToolNames.GET_SCENE to "scene",
    CanvasToolNames.GET_LAYOUT to "layout",
    CanvasToolNames.REPLACE_SCENE to "replace-scene",
    CanvasToolNames.EXPORT_SVG to "export-svg",
    CanvasToolNames.LIST to "list",
    CanvasToolNames.RENDER_PREVIEW to "preview",
    CanvasToolNames.COMPOSE_GUIDE to "guide",
)

/** Ops about no one canvas: listing them, reading the compose guide. */
private val NO_CANVAS = setOf(CanvasToolNames.LIST, CanvasToolNames.COMPOSE_GUIDE)

private const val TITLE = "title"
private const val ITEMS = "items"
private const val OPS = "ops"
private const val KIND = "kind"
private const val TYPE = "type"
private const val CHILDREN = "children"
private const val INDENT = "  "
private const val PREVIEW_LINES = 12
private const val FAILURE_SCAN_CHARS = 64
private val ITEM_TITLE_KEYS = listOf("title", "text", "label", "markdown")
private val OP_ID_KEYS = listOf("elementId", "documentId", "id")
