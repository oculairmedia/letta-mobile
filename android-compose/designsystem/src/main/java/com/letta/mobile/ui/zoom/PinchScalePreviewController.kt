package com.letta.mobile.ui.zoom

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.round

@Stable
class PinchScalePreviewController(
    private val minScale: Float,
    private val maxScale: Float,
    private val step: Float,
) {
    var isPinching by mutableStateOf(false)
        private set

    var transientScale by mutableFloatStateOf(1f)
        private set

    private var baseScale = 1f

    fun begin(activeScale: Float) {
        baseScale = activeScale.coerceIn(minScale, maxScale)
        transientScale = 1f
        isPinching = true
    }

    fun applyZoom(zoomChange: Float) {
        val visualScale = (baseScale * transientScale * zoomChange).coerceIn(minScale, maxScale)
        transientScale = visualScale / baseScale
    }

    fun finish(): Float {
        val snapped = snap(baseScale * transientScale)
        baseScale = snapped
        transientScale = 1f
        isPinching = false
        return snapped
    }

    fun cancel() {
        transientScale = 1f
        isPinching = false
    }

    private fun snap(value: Float): Float = (round(value.coerceIn(minScale, maxScale) / step) * step)
        .coerceIn(minScale, maxScale)
}
