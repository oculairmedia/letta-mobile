package com.letta.mobile.data.canvas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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

    /** What export_svg returns until a real exporter runs where the tools do. */
    const val PLACEHOLDER_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 600\"></svg>"

    val create = CanvasToolDefinition(
        CREATE,
        "Create a new canvas document. Returns the created canvas_id.",
        objectSchema(optional = listOf("title", "conversation_id")),
    )

    val getScene = CanvasToolDefinition(
        GET_SCENE,
        "Get the current DrawBox scene JSON and revision for a canvas.",
        objectSchema(required = listOf("canvas_id")),
    )

    val replaceScene = CanvasToolDefinition(
        REPLACE_SCENE,
        "Replace the DrawBox scene JSON for a canvas, incrementing its revision.",
        objectSchema(required = listOf("canvas_id", "scene_json")),
    )

    val applyOps = CanvasToolDefinition(
        APPLY_OPS,
        "Apply a sequence of Canvas operations to the canvas. Besides element ops, " +
            "set_document {document_id, document_json, frame?, color?, style?} places a block-document note on the board " +
            "(frame = {x, y, width, height} in world units, color = #rrggbb or #00000000 for plain text, " +
            "style = {fontScale?, fontFamily? sans|serif|mono, textColor?, align? start|center|end}) and " +
            "remove_document {document_id} takes it off.",
        objectSchema(required = listOf("canvas_id"), arrays = listOf("ops")),
    )

    val exportSvg = CanvasToolDefinition(
        EXPORT_SVG,
        "Export the SVG representation of a canvas.",
        objectSchema(required = listOf("canvas_id")),
    )

    val list = CanvasToolDefinition(
        LIST,
        "List canvas IDs for a conversation, or every canvas the calling agent owns.",
        objectSchema(optional = listOf("conversation_id")),
    )

    val all: List<CanvasToolDefinition> = listOf(create, getScene, replaceScene, applyOps, exportSvg, list)

    /** An object of string properties: [required] ones, [optional] ones, and required [arrays]. */
    private fun objectSchema(
        required: List<String> = emptyList(),
        optional: List<String> = emptyList(),
        arrays: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            (required + optional).forEach { putJsonObject(it) { put("type", "string") } }
            arrays.forEach { putJsonObject(it) { put("type", "array") } }
        }
        val mandatory = required + arrays
        if (mandatory.isNotEmpty()) put("required", buildJsonArray { mandatory.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }
}
