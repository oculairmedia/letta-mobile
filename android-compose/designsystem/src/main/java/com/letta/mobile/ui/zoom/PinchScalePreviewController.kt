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
    init {
        require(minScale.isValidFiniteScale() && minScale > 0f) { "minScale must be finite and greater than 0" }
        require(maxScale.isValidFiniteScale() && maxScale >= minScale) { "maxScale must be finite and greater than or equal to minScale" }
        require(step.isValidFiniteScale() && step > 0f) { "step must be finite and greater than 0" }
    }

    var isPinching by mutableStateOf(false)
        private set

    var transientScale by mutableFloatStateOf(1f)
        private set

    private var baseScale = 1f
    private var pendingCommittedScale: Float? = null

    fun begin(activeScale: Float) {
        baseScale = activeScale.coerceIn(minScale, maxScale)
        transientScale = 1f
        pendingCommittedScale = null
        isPinching = true
    }

    fun applyZoom(zoomChange: Float) {
        val visualScale = (baseScale * transientScale * zoomChange).coerceIn(minScale, maxScale)
        transientScale = visualScale / baseScale
    }

    fun finishPreview(): Float {
        val snapped = snap(baseScale * transientScale)
        pendingCommittedScale = snapped
        isPinching = false
        return snapped
    }

    fun syncCommittedScale(activeScale: Float) {
        val pending = pendingCommittedScale ?: return
        if (activeScale == pending) {
            baseScale = pending
            transientScale = 1f
            pendingCommittedScale = null
        }
    }

    fun visualScaleFor(activeScale: Float): Float {
        val pending = pendingCommittedScale
        return if (pending != null && activeScale == pending) 1f else transientScale
    }

    fun cancel() {
        transientScale = 1f
        pendingCommittedScale = null
        isPinching = false
    }

    private fun snap(value: Float): Float = (round(value.coerceIn(minScale, maxScale) / step) * step)
        .coerceIn(minScale, maxScale)
}

private fun Float.isValidFiniteScale(): Boolean = !isNaN() && !isInfinite()
