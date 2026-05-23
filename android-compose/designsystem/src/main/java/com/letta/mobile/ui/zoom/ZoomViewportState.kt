package com.letta.mobile.ui.zoom

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

@Stable
class ZoomViewportState(
    private val minScale: Float = 1f,
    private val maxScale: Float = 4f,
    initialScale: Float = minScale,
) {
    var scale by mutableFloatStateOf(initialScale.coerceIn(minScale, maxScale))
        private set

    var pan by mutableStateOf(Offset.Zero)
        private set

    fun reset() {
        scale = minScale
        pan = Offset.Zero
    }

    fun setAbsoluteScale(value: Float) {
        scale = value.coerceIn(minScale, maxScale)
        pan = Offset.Zero
    }

    fun onTransform(
        zoomChange: Float,
        panChange: Offset,
        centroid: Offset,
        viewportSize: IntSize,
    ) {
        val previousScale = scale
        val nextScale = (scale * zoomChange).coerceIn(minScale, maxScale)
        val appliedScale = if (previousScale == 0f) 1f else nextScale / previousScale

        scale = nextScale
        pan = if (nextScale <= minScale + MIN_SCALE_EPSILON) {
            Offset.Zero
        } else {
            val viewportCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
            val centroidFromCurrentContent = centroid - viewportCenter - pan
            pan + panChange + centroidFromCurrentContent * (1f - appliedScale)
        }
    }

    private companion object {
        const val MIN_SCALE_EPSILON = 0.01f
    }
}
