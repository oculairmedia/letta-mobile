package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.timeline_font_scale_percent
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * letta-mobile-bglj6.1: pinch-to-zoom of the timeline's text scale, in common pointer input.
 *
 * Lifted from Android's ChatMessageListPinch + PinchScalePreviewController without the
 * Choreographer frame-budget sampler (Android-only telemetry). While pinching, rows stay laid out
 * at [restingScale] and only the list's draw layer follows the gesture ([layerScale], read in a
 * graphicsLayer block), so a pinch frame recomposes and re-lays out nothing. On release the
 * snapped scale is reported once through ChatActions.setFontScale and rows re-lay out at it once;
 * it shows until the owner's committed scale moves (to it, or anywhere else: a host change such
 * as desktop's Ctrl+scroll then wins).
 */
/** A text scale the owner holds (1 is the default size): what the rows lay out at, before any pinch. */
@JvmInline
internal value class TextScale(val value: Float)

@Stable
internal class TimelinePinchScale(
    range: ClosedFloatingPointRange<Float> = MIN_SCALE..MAX_SCALE,
    private val step: Float = STEP,
) {
    private val minScale = range.start
    private val maxScale = range.endInclusive

    var isPinching by mutableStateOf(false)
        private set
    private var base by mutableFloatStateOf(1f)
    private var transient by mutableFloatStateOf(1f)
    private var pending by mutableStateOf<Float?>(null)

    /** The owner's committed scale when the gesture began; [pending] holds only until it moves. */
    private var committedAtBegin = TextScale(1f)

    /** The scale rows lay out at: a just-committed value, else [committed]. Never the live gesture. */
    fun restingScale(committed: TextScale): Float {
        return pending?.takeIf { sameScale(committed, committedAtBegin) } ?: committed.value
    }

    /** The scale the read-out shows: the live gesture, else [restingScale]. */
    fun effectiveScale(committed: TextScale): Float {
        return if (isPinching) liveScale() else restingScale(committed)
    }

    /** The list layer's scale over the rows' layout: the live gesture over its base. Draw phase only. */
    val layerScale: Float
        get() = currentLayerScale()

    private fun currentLayerScale(): Float {
        return if (isPinching) liveScale() / base else 1f
    }

    private fun liveScale(): Float = (base * transient).coerceIn(minScale, maxScale)

    /** The owner's committed scale changed: whatever it now is supersedes a pending commit. */
    fun onCommittedChanged(committed: TextScale) {
        if (pending != null && !sameScale(committed, committedAtBegin)) pending = null
    }

    fun begin(committed: TextScale) {
        committedAtBegin = committed
        base = committed.value.coerceIn(minScale, maxScale)
        transient = 1f
        pending = null
        isPinching = true
    }

    fun applyZoom(zoom: Float) {
        transient = (base * transient * zoom).coerceIn(minScale, maxScale) / base
    }

    /** Ends the gesture; returns the snapped scale to commit. */
    fun finish(): Float {
        val snapped = ((liveScale() / step).roundToInt() * step).coerceIn(minScale, maxScale)
        // A pinch back to where it began leaves the rows as they are: nothing to hold.
        pending = snapped.takeUnless { sameScale(TextScale(it), committedAtBegin) }
        isPinching = false
        transient = 1f
        return snapped
    }

    fun cancel() {
        isPinching = false
        transient = 1f
    }

    companion object {
        /** Android's settings store range (CachedSettingsRepository.setChatFontScale). */
        const val MIN_SCALE: Float = 0.7f
        const val MAX_SCALE: Float = 1.6f
        const val STEP: Float = 0.02f
        private const val EPSILON: Float = 0.0001f

        private fun sameScale(a: TextScale, b: TextScale): Boolean = abs(a.value - b.value) < EPSILON
    }
}

/**
 * Watches every gesture on the Initial pass and only consumes it once a second finger is down, so
 * one-finger scroll and taps still reach the list and rows. [enabled] false is a no-op modifier.
 */
internal fun Modifier.timelinePinchZoom(
    enabled: Boolean,
    pinch: TimelinePinchScale,
    committedScale: () -> TextScale,
    onCommit: (Float) -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(pinch) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var pinching = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    if (!pinching) {
                        pinching = true
                        pinch.begin(committedScale())
                    }
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        event.changes.forEach { it.consume() }
                        pinch.applyZoom(zoom)
                    }
                }
            } while (event.changes.any { it.pressed })
            if (pinching) onCommit(pinch.finish()) else pinch.cancel()
        }
    }
}

@Composable
internal fun rememberTimelinePinch(
    enabled: Boolean,
    committedScale: TextScale,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
): Pair<TimelinePinchScale, Modifier> {
    val pinch = remember(range) { TimelinePinchScale(range) }
    LaunchedEffect(pinch, committedScale) { pinch.onCommittedChanged(committedScale) }
    val committed by rememberUpdatedState(committedScale)
    val commit by rememberUpdatedState(onCommit)
    val modifier = Modifier.timelinePinchZoom(enabled, pinch, { committed }, { commit(it) })
    return pinch to modifier
}

/**
 * Where the list's pinch layer scales from: the bottom edge, which the reversed list is anchored
 * to. The commit re-lays out the rows from that same edge (the newest row, or the scrolled-to one,
 * stays put and the rest grow or shrink above it), so the release lands where the preview was.
 */
internal val TimelinePinchOrigin: TransformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)

/** The read-out while pinching, in its own scope: the live scale recomposes only this. */
@Composable
internal fun PinchScaleReadout(pinch: TimelinePinchScale, committedScale: TextScale, modifier: Modifier = Modifier) {
    if (pinch.isPinching) PinchScaleIndicator(pinch.effectiveScale(committedScale), modifier)
}

/** The live "112%" read-out while pinching (Android's pinch indicator). */
@Composable
private fun PinchScaleIndicator(scale: Float, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.testTag(ChatTimelineTags.FONT_SCALE),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            text = stringResource(Res.string.timeline_font_scale_percent, (scale * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
        )
    }
}
