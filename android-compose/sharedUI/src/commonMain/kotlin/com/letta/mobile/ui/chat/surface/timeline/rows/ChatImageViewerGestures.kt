package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/*
 * letta-mobile-bglj6.1.23: the legacy Android viewer's gestures (feature-chat ChatImageViewer),
 * on commonMain so the shared page's viewer has them: pinch to zoom 1-5x around the fingers,
 * double-tap to 2.5x (and back), pan while zoomed, and a vertical swipe at rest to dismiss.
 * The transform math is plain functions so it is testable without a pointer.
 */

internal const val MinImageScale = 1f
internal const val DoubleTapImageScale = 2.5f
internal const val MaxImageScale = 5f
internal const val SwipeDismissThresholdPx = 160f

/** Treated as "at rest" below this, so a pinch that settles a hair over 1x still swipes. */
private const val ZoomedScale = 1.02f

/** The image's zoom and its pan from centre, in px. */
@Immutable
internal data class ImageTransformState(
    val scale: Float = MinImageScale,
    val offset: Offset = Offset.Zero,
) {
    val zoomed: Boolean get() = scale > ZoomedScale
}

/** One pinch/pan frame: scale about [centroid], then pan, kept inside the image's bounds. */
internal fun applyImageTransformGesture(
    state: ImageTransformState,
    gesture: ImageGestureFrame,
    containerSize: Size,
): ImageTransformState {
    val safeState = sanitizeImageTransform(state, containerSize)
    val zoom = gesture.zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
    val pan = gesture.pan.finiteOrNull() ?: Offset.Zero
    val center = containerSize.centerOrZero()
    val centroid = gesture.centroid.finiteOrNull() ?: center
    val nextScale = (safeState.scale * zoom).coerceIn(MinImageScale, MaxImageScale)
    if (nextScale <= MinImageScale) return ImageTransformState()
    val shift = centroid - center
    val ratio = nextScale / safeState.scale
    val nextOffset = (safeState.offset + shift) * ratio - shift + pan
    return ImageTransformState(nextScale, clampImageOffset(nextOffset, nextScale, containerSize))
}

/** A pinch/pan frame's zoom factor, pan and centroid. */
@Immutable
internal data class ImageGestureFrame(val zoom: Float, val pan: Offset, val centroid: Offset)

/** The pan that keeps a [scale]d image covering its container: none at rest. */
internal fun clampImageOffset(offset: Offset, scale: Float, containerSize: Size): Offset {
    if (!pans(scale, containerSize)) return Offset.Zero
    val maxX = (containerSize.width * scale - containerSize.width) / 2f
    val maxY = (containerSize.height * scale - containerSize.height) / 2f
    if (!maxX.isFinite() || !maxY.isFinite()) return Offset.Zero
    val safe = Offset(offset.x.takeIf { it.isFinite() } ?: 0f, offset.y.takeIf { it.isFinite() } ?: 0f)
    return Offset(safe.x.coerceIn(-maxX, maxX), safe.y.coerceIn(-maxY, maxY))
}

/** [state] with its scale in range and its pan inside the bounds. */
internal fun sanitizeImageTransform(state: ImageTransformState, containerSize: Size): ImageTransformState {
    val scale = state.scale.takeIf { it.isFinite() }?.coerceIn(MinImageScale, MaxImageScale) ?: MinImageScale
    if (scale <= MinImageScale) return ImageTransformState()
    return ImageTransformState(scale, clampImageOffset(state.offset, scale, containerSize))
}

/** Double-tap: zoomed, back to rest; at rest, 2.5x about the tap. */
internal fun doubleTapImageTransform(state: ImageTransformState, tap: Offset, containerSize: Size): ImageTransformState {
    if (state.scale > MinImageScale) return ImageTransformState()
    return applyImageTransformGesture(state, ImageGestureFrame(DoubleTapImageScale, Offset.Zero, tap), containerSize)
}

/** A one-finger vertical swipe past the threshold, at rest, dismisses the viewer. */
internal fun shouldDismissImageViewer(scale: Float, verticalDragDistance: Float): Boolean =
    scale <= ZoomedScale && abs(verticalDragDistance) > SwipeDismissThresholdPx

/** A zoomed image in a measured container can pan; at rest (or unmeasured) it cannot. */
private fun pans(scale: Float, containerSize: Size): Boolean = scale.isFinite() && scale > MinImageScale && containerSize.isUsable()

private fun Offset.finiteOrNull(): Offset? = takeIf { x.isFinite() && y.isFinite() }

private fun Size.isUsable(): Boolean = width.isFinite() && height.isFinite() && width > 0f && height > 0f

private fun Size.centerOrZero(): Offset = if (width.isFinite() && height.isFinite()) Offset(width / 2f, height / 2f) else Offset.Zero

/**
 * The viewer page's gestures, writing [transform]: double-tap toggles 2.5x, pinch / pan (or any
 * drag while zoomed) transform the image, and a vertical swipe at rest calls [onDismiss]. At
 * rest a horizontal drag is left to the pager.
 */
internal fun Modifier.zoomableImageGestures(
    key: Any,
    transform: MutableState<ImageTransformState>,
    containerSize: () -> Size,
    onDismiss: () -> Unit,
): Modifier = this
    .pointerInput(key) {
        detectTapGestures(onDoubleTap = { tap -> transform.value = doubleTapImageTransform(transform.value, tap, containerSize()) })
    }
    .pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val gesture = ViewerGesture(transform, containerSize)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                gesture.onEvent(event)
            } while (event.changes.any { it.pressed })
            transform.value = sanitizeImageTransform(transform.value, containerSize())
            if (gesture.dismisses()) onDismiss()
        }
    }

/** One gesture's progress: whether it transformed the image, and how far it dragged vertically at rest. */
private class ViewerGesture(
    private val transform: MutableState<ImageTransformState>,
    private val containerSize: () -> Size,
) {
    private var transformed = false
    private var zoomedAtStart = transform.value.zoomed
    private var verticalDrag = 0f

    fun onEvent(event: PointerEvent) {
        val pressed = event.changes.count { it.pressed }
        val zoom = event.calculateZoom()
        when {
            transforms(pressed, zoom) -> applyTransform(event, zoom)
            !zoomedAtStart && pressed == 1 -> trackSwipe(event)
        }
        zoomedAtStart = zoomedAtStart || transform.value.zoomed
    }

    /** Two fingers, a pinch, or any drag while zoomed moves the image. */
    private fun transforms(pressed: Int, zoom: Float): Boolean {
        if (pressed == 0) return false
        return pressed >= 2 || zoom != 1f || transform.value.zoomed
    }

    private fun applyTransform(event: PointerEvent, zoom: Float) {
        transformed = true
        val frame = ImageGestureFrame(zoom, event.calculatePan(), event.calculateCentroid(useCurrent = true))
        transform.value = applyImageTransformGesture(transform.value, frame, containerSize())
        event.changes.forEach { it.consume() }
    }

    /** At rest, one finger: a vertical drag is the swipe to dismiss; a horizontal one is the pager's. */
    private fun trackSwipe(event: PointerEvent) {
        val pan = event.calculatePan()
        verticalDrag += pan.y
        if (abs(pan.y) > abs(pan.x)) event.changes.forEach { it.consume() }
    }

    fun dismisses(): Boolean = !transformed && shouldDismissImageViewer(transform.value.scale, verticalDrag)
}
