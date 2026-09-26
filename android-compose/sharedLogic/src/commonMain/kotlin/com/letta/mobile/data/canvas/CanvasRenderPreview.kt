package com.letta.mobile.data.canvas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Pixels and device configuration supplied to the real Compose renderer, not a geometry estimator. */
@Serializable
data class CanvasPreviewViewport(
    val widthPx: Int,
    val heightPx: Int,
    val density: Double,
    val fontScale: Double = 1.0,
    val zoom: Double = 1.0,
    val cameraX: Double = 0.0,
    val cameraY: Double = 0.0,
    val fitToContent: Boolean = true,
) {
    companion object {
        fun parse(input: JsonObject): CanvasPreviewViewport {
            fun number(key: String): Double? = (input[key] as? JsonPrimitive)?.doubleOrNull
            val width = (input["width_px"] as? JsonPrimitive)?.intOrNull
            val height = (input["height_px"] as? JsonPrimitive)?.intOrNull
            val density = number("density")
            require(width != null && width in 1..4096 && height != null && height in 1..4096) {
                "width_px and height_px must be integers from 1 to 4096"
            }
            require(density != null && density.isFinite() && density in 0.5..8.0) { "density must be from 0.5 to 8" }
            val fontScale = number("font_scale") ?: 1.0
            val zoom = number("zoom") ?: 1.0
            val x = number("camera_x") ?: 0.0
            val y = number("camera_y") ?: 0.0
            require(fontScale.isFinite() && fontScale in 0.5..4.0) { "font_scale must be from 0.5 to 4" }
            require(zoom.isFinite() && zoom in 0.05..4.0) { "zoom must be from 0.05 to 4" }
            require(x.isFinite() && y.isFinite() && x in -100000.0..100000.0 && y in -100000.0..100000.0) {
                "camera offset must be finite and within 100000 pixels"
            }
            val fit = (input["fit_to_content"] as? JsonPrimitive)?.booleanOrNull ?: true
            return CanvasPreviewViewport(width, height, density, fontScale, zoom, x, y, fit)
        }
    }
}

/** Only a renderer that draws the same DrawBox/Compose scene as the mobile app may implement this. */
fun interface CanvasPreviewRenderer {
    suspend fun render(sceneJson: String, viewport: CanvasPreviewViewport): CanvasPreviewRender
}

@Serializable
data class CanvasPreviewRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Serializable
data class CanvasPreviewBounds(
    val id: String,
    val kind: String,
    val world: CanvasPreviewRect,
    val screen: CanvasPreviewRect,
    val text: CanvasPreviewRect? = null,
)

@Serializable
data class CanvasPreviewDiagnostic(val code: String, val elementIds: List<String>, val detail: String)

@Serializable
data class CanvasPreviewRender(
    val imagePngBase64: String,
    val bounds: List<CanvasPreviewBounds>,
    val diagnostics: List<CanvasPreviewDiagnostic>,
)

@Serializable
data class CanvasPreviewResult(
    val canvasId: String,
    val revision: Long,
    val candidate: Boolean,
    val viewport: CanvasPreviewViewport,
    val render: CanvasPreviewRender,
)
