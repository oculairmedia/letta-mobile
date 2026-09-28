package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One input of a canvas tool: a string unless [type] says otherwise, with what the model should know of it. */
private data class ToolParam(
    val name: String,
    val type: String = "string",
    val required: Boolean = false,
    val description: String? = null,
    val items: JsonObject? = null,
) {
    fun schema(): JsonObject = buildJsonObject {
        put("type", type)
        description?.let { put("description", it) }
        items?.let { put("items", it) }
    }
}

/** What the model sees of one canvas tool: its name, what it does and what it takes. */
data class CanvasToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * The `canvas.*` tools as the model sees them, whoever runs them: an app's own runtime
 * ([CanvasExternalTools]) or the host's ([HostCanvasTools]). One contract, so an agent's canvas
 * calls mean the same thing on either.
 */
object CanvasToolContract {
    const val CREATE = "canvas.create"
    const val GET_SCENE = "canvas.get_scene"
    const val REPLACE_SCENE = "canvas.replace_scene"
    const val APPLY_OPS = "canvas.apply_ops"
    const val EXPORT_SVG = "canvas.export_svg"
    const val LIST = "canvas.list"
    const val RENDER_PREVIEW = "canvas.render_preview"

    /**
     * What export_svg answers until a real exporter runs where the tools do (letta-mobile-qsq7v).
     * It used to return a constant empty SVG, which an agent took for the canvas's true content.
     * The tool is not offered ([all] leaves it out); a call that names it anyway is told plainly.
     */
    const val EXPORT_SVG_NOT_IMPLEMENTED =
        "canvas.export_svg is not implemented yet: nothing renders a canvas to SVG where the tools run. " +
            "Use canvas.get_scene to read the canvas."

    /** How every canvas-reading tool is told which canvas: optional, defaulting to the conversation's. */
    private val canvasIdParam = ToolParam(
        "canvas_id",
        description = "The canvas to use. Omit it to use the canvas of the conversation you are in " +
            "(created on first use); only name one to reach a different canvas from canvas.list.",
    )

    /** Checks a write without making it (letta-mobile-qygvv.30). */
    private val dryRunParam = ToolParam(
        CanvasDryRun.PARAM,
        type = "boolean",
        description = "true to check the write without publishing it: the result says valid true/false and lists " +
            "each problem (op_index, invariant, detail). Nothing is published either way.",
    )

    private const val DRY_RUN_HINT =
        "Pass dry_run: true to check a batch against the current board first without publishing it. "

    val create = CanvasToolDefinition(
        CREATE,
        "Create a new canvas document. Returns the created canvas_id. The conversation you are in already " +
            "has a canvas, which every other canvas tool uses when given no canvas_id.",
        objectSchema(ToolParam("title"), ToolParam("conversation_id")),
    )

    val getScene = CanvasToolDefinition(
        GET_SCENE,
        "Get a canvas's current scene (DrawBox JSON) and revision. With no canvas_id, reads the canvas of the " +
            "conversation you are in. The result's schema_hint summarises the element format.",
        objectSchema(canvasIdParam),
    )

    val replaceScene = CanvasToolDefinition(
        REPLACE_SCENE,
        "Replace the whole drawing of a canvas (block-document notes are kept). With no canvas_id, draws on the " +
            "canvas of the conversation you are in. A scene the apps cannot draw is refused with the reason and " +
            "nothing is published; so is one that leaves the board inconsistent (a note label whose shape the new " +
            "scene drops, an arrow bound to a shape it drops). " + DRY_RUN_HINT + CanvasSceneSchema.description,
        objectSchema(
            canvasIdParam,
            ToolParam(
                "scene_json",
                required = true,
                description = "The scene as a JSON string: {\"bgColor\":\"#rrggbbaa\",\"elements\":[...]}, " +
                    "elements of type Shape, Text, Path or Image as this tool's description sets out.",
            ),
            dryRunParam,
        ),
    )

    val applyOps = CanvasToolDefinition(
        APPLY_OPS,
        "Apply a sequence of Canvas operations to a canvas (with no canvas_id, the canvas of the conversation you " +
            "are in). Each op is an object with a \"type\": " +
            "add_element {elementId, elementJson} adds one element and update_element {elementId, elementJson} " +
            "replaces it whole (elementJson is one element as a JSON string, in the scene format below); " +
            "remove_element {elementId}; set_background {colorHex \"#rrggbbaa\"}; " +
            "set_document {documentId, documentJson, frame?, color?, style?} places a block-document note on the board " +
            "(frame = {x, y, width, height} in world units, color = #rrggbb or #00000000 for plain text, " +
            "style = {fontScale?, fontFamily? sans|serif|mono, textColor?, align? start|center|end}) and " +
            "remove_document {documentId} takes it off. opId, actorId and lamport are filled in by the host. " +
            "The batch is all or nothing: it is applied to a copy of the board first, and if any op's element cannot be " +
            "drawn or the board it leaves is inconsistent (update_element/remove_element/remove_document of an id " +
            "that is not there, add_element of an id that is, a note label whose shape is gone, an arrow bound to a " +
            "missing shape or note, a documentJson without a \"blocks\" array, a scene over the size limits) the " +
            "whole batch is refused, naming each op by index and the rule it breaks, and nothing is published. " +
            DRY_RUN_HINT +
            CanvasSceneSchema.description,
        objectSchema(
            canvasIdParam,
            ToolParam(
                "ops",
                type = "array",
                required = true,
                description = "The ops, in order, e.g. [{\"type\":\"add_element\",\"elementId\":\"box-1\"," +
                    "\"elementJson\":\"{\\\"type\\\":\\\"Shape\\\",...}\"}].",
                items = buildJsonObject { put("type", "object") },
            ),
            dryRunParam,
        ),
    )

    val renderPreview = CanvasToolDefinition(
        RENDER_PREVIEW,
        "Render a proposed scene or ops without publishing, or the current published revision, using the mobile " +
            "Compose canvas renderer. Returns an image and measured layout diagnostics only when a renderer is available. " +
            "A structural dry_run is not a visual preview. Specify exactly one of scene_json or ops, or neither " +
            "for the current revision. Camera offset is in screen pixels; fit_to_content defaults to true.",
        objectSchema(
            canvasIdParam,
            ToolParam("scene_json", description = "Proposed DrawBox scene as a JSON string; not published."),
            ToolParam("ops", type = "array", description = "Proposed canvas operations; not published.",
                items = buildJsonObject { put("type", "object") }),
            ToolParam("width_px", type = "integer", required = true),
            ToolParam("height_px", type = "integer", required = true),
            ToolParam("density", type = "number", required = true),
            ToolParam("font_scale", type = "number"),
            ToolParam("zoom", type = "number"),
            ToolParam("camera_x", type = "number"),
            ToolParam("camera_y", type = "number"),
            ToolParam("fit_to_content", type = "boolean"),
        ),
    )

    val exportSvg = CanvasToolDefinition(
        EXPORT_SVG,
        "Export the SVG representation of a canvas (with no canvas_id, the conversation's canvas).",
        objectSchema(canvasIdParam),
    )

    val list = CanvasToolDefinition(
        LIST,
        "List the canvases for a conversation, or every canvas you may read. Each entry says whether it is " +
            "current (the canvas of the conversation you are in, listed first). You rarely need this: the other " +
            "tools use the current canvas when given no canvas_id.",
        objectSchema(ToolParam("conversation_id")),
    )

    /**
     * The tools offered to agents. [exportSvg] is not among them until it renders the real canvas: a
     * tool that always fails only costs an agent turns (see ExternalToolRegistry.factoryDefault).
     */
    // Only advertise preview when a mobile renderer bridge is actually connected.
    val all: List<CanvasToolDefinition> = listOf(create, getScene, replaceScene, applyOps, list)
    val withPreview: List<CanvasToolDefinition> = all + renderPreview

    /** An object of [params]; the [ToolParam.required] ones are listed as required. */
    private fun objectSchema(vararg params: ToolParam): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { params.forEach { put(it.name, it.schema()) } }
        val mandatory = params.filter { it.required }.map { it.name }
        if (mandatory.isNotEmpty()) put("required", buildJsonArray { mandatory.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
}
