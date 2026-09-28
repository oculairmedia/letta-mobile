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
        fun parse(input: JsonObject): CanvasPreviewViewport = CanvasPreviewViewport(
            widthPx = input.requiredInt("width_px", 1..4096),
            heightPx = input.requiredInt("height_px", 1..4096),
            density = input.requiredNumber("density", 0.5..8.0),
            fontScale = input.optionalNumber("font_scale", 1.0, 0.5..4.0),
            zoom = input.optionalNumber("zoom", 1.0, 0.05..4.0),
            cameraX = input.optionalNumber("camera_x", 0.0, -100000.0..100000.0),
            cameraY = input.optionalNumber("camera_y", 0.0, -100000.0..100000.0),
            fitToContent = input.optionalBoolean("fit_to_content", true),
        )

        private fun JsonObject.requiredInt(key: String, range: IntRange): Int {
            val value = (this[key] as? JsonPrimitive)?.intOrNull
            require(value != null && value in range) { "$key must be an integer from ${range.first} to ${range.last}" }
            return value
        }

        private fun JsonObject.requiredNumber(key: String, range: ClosedFloatingPointRange<Double>): Double {
            val value = (this[key] as? JsonPrimitive)?.doubleOrNull
            require(value != null && value.isFinite() && value in range) { "$key must be from ${range.start} to ${range.endInclusive}" }
            return value
        }

        private fun JsonObject.optionalNumber(key: String, default: Double, range: ClosedFloatingPointRange<Double>): Double =
            if (key in this) requiredNumber(key, range) else default

        private fun JsonObject.optionalBoolean(key: String, default: Boolean): Boolean {
            if (key !in this) return default
            return requireNotNull((this[key] as? JsonPrimitive)?.booleanOrNull) { "$key must be a boolean" }
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
