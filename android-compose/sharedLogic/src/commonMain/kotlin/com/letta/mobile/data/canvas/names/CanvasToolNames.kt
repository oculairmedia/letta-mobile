package com.letta.mobile.data.canvas.names

/**
 * The `canvas_*` tool names, on their own so code that only names the tools (a tool step's
 * presenter) reads them without depending on the canvas model. [com.letta.mobile.data.canvas.CanvasToolContract]
 * defines the tools under these names.
 */
object CanvasToolNames {
    const val CREATE = "canvas_create"
    const val GET_SCENE = "canvas_get_scene"
    const val GET_LAYOUT = "canvas_get_layout"
    const val REPLACE_SCENE = "canvas_replace_scene"
    const val APPLY_OPS = "canvas_apply_ops"
    const val EXPORT_SVG = "canvas_export_svg"
    const val LIST = "canvas_list"
    const val RENDER_PREVIEW = "canvas_render_preview"
    const val COMPOSE = "canvas_compose"
    const val COMPOSE_GUIDE = "canvas_compose_guide"

    /** Every canvas tool. */
    val ALL: List<String> = listOf(
        CREATE, GET_SCENE, GET_LAYOUT, REPLACE_SCENE, APPLY_OPS, EXPORT_SVG, LIST, RENDER_PREVIEW, COMPOSE, COMPOSE_GUIDE,
    )
}
