package com.letta.mobile.ui.chat.surface

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.session.ChatDockRect
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * letta-mobile-bglj6.1: the docked panel grows into the full-screen page, and shrinks back, as
 * one continuous transform instead of a jump. One progress (0 docked .. 1 full screen) drives
 * both layers of the page: the docked panel's rect goes from its own bounds to the whole area,
 * its corners square off, its shadow drops away and its fill turns into the page background,
 * which also fades in over the canvas; the page layer follows the same rect and fades in over
 * the panel's content as that fades out.
 *
 * Each layer is composed at ONE place for as long as it is on screen, at rest and in motion
 * alike. The old version swapped the resting panel and page for a separate morph layer at the
 * start and end of every transition, so the panel's conversation, the composer, the mascot seat
 * and the timeline were disposed and composed again on the frames the person was looking at:
 * markdown came back blank for a frame, the mascot vanished, the companion slot closed and
 * reopened. Now the panel stays composed until it has faded out (the page covers it), and the
 * page from the moment it starts fading in.
 */

/** Which of the three things the canvas page draws over its canvas right now. */
internal enum class SurfaceMorphPhase { Docked, Morphing, FullScreen }

/**
 * Progress towards [fullScreen]: eased, never overshooting, and from wherever it is, so a
 * toggle mid-way reverses smoothly in the time the remaining distance takes. Under reduced
 * motion it jumps.
 */
@Composable
internal fun rememberSurfaceMorphProgress(fullScreen: Boolean): Animatable<Float, AnimationVector1D> {
    val reducedMotion = LocalReducedMotion.current
    val progress = remember { Animatable(morphTarget(fullScreen)) }
    LaunchedEffect(fullScreen, reducedMotion) {
        val target = morphTarget(fullScreen)
        if (reducedMotion) {
            progress.snapTo(target)
        } else {
            val millis = (ChatMotionTokens.SurfaceMorph.MILLIS * abs(target - progress.value)).roundToInt()
            progress.animateTo(target, tween(millis, easing = ChatMotionTokens.SurfaceMorph.easing))
        }
    }
    return progress
}

/** Settled in a mode, or on the way; reduced motion is always settled in the presented mode. */
@Composable
internal fun surfaceMorphPhase(progress: Animatable<Float, AnimationVector1D>, fullScreen: Boolean): SurfaceMorphPhase {
    val reducedMotion = LocalReducedMotion.current
    val phase by remember(progress, fullScreen, reducedMotion) {
        derivedStateOf {
            when {
                !reducedMotion && progress.value != morphTarget(fullScreen) -> SurfaceMorphPhase.Morphing
                fullScreen -> SurfaceMorphPhase.FullScreen
                else -> SurfaceMorphPhase.Docked
            }
        }
    }
    return phase
}

private fun morphTarget(fullScreen: Boolean): Float = if (fullScreen) 1f else 0f

/**
 * The morph as the layers read it. [fraction] is read at layout and draw time only, so a frame
 * of the morph relayouts and redraws without recomposing; [morphing] changes at the two ends.
 */
@Immutable
internal class SurfaceMorph(val fraction: () -> Float, val morphing: Boolean) {
    companion object {
        /** At rest in the docked mode: the panel is just the panel. */
        val Docked = SurfaceMorph(fraction = { 0f }, morphing = false)
    }
}

/** The panel's rect at [fraction] of the way from [from] to the whole [widthDp] x [heightDp] area. */
internal fun morphRect(from: ChatDockRect, widthDp: Float, heightDp: Float, fraction: Float): ChatDockRect =
    if (fraction <= 0f) from else lerpRect(from, ChatDockRect(0f, 0f, widthDp, heightDp), fraction)

/** The page background fading in over the canvas; opaque once the page is up. */
@Composable
internal fun MorphBackdrop(fraction: () -> Float, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.background
    Box(modifier.fillMaxSize().drawBehind { drawRect(color, alpha = fraction()) })
}

/**
 * The full-screen page's layer over the canvas: the whole area at rest; while the morph runs it
 * follows the panel's rect (from where [dock] places it), clipped to its rounding, and fades in
 * over the panel's content. It takes every touch inside it, as the opaque page always did.
 * Hidden from accessibility while it is on its way.
 */
@Composable
internal fun MorphPageLayer(
    dock: ChatDockState,
    morph: SurfaceMorph,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // The panel rises above the keyboard (DockedChatPanel pads for it); the page does not.
    val imeDp = with(density) { WindowInsets.ime.getBottom(density).toDp().value }
    val fraction = morph.fraction
    Box(
        modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val widthDp = constraints.maxWidth.toDp().value
                val heightDp = constraints.maxHeight.toDp().value
                val from = dock.rectIn(widthDp, (heightDp - imeDp).coerceAtLeast(0f))
                val rect = lerpRect(from, ChatDockRect(0f, 0f, widthDp, heightDp), fraction())
                val placeable = measurable.measure(
                    Constraints.fixed(rect.width.dp.roundToPx().coerceAtLeast(0), rect.height.dp.roundToPx().coerceAtLeast(0)),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(rect.left.dp.roundToPx(), rect.top.dp.roundToPx())
                }
            }
            .then(if (morph.morphing) Modifier.testTag(SURFACE_MORPH_TAG) else Modifier)
            .graphicsLayer {
                shape = RoundedCornerShape(LettaDimens.Radius.lg.toPx() * (1f - fraction()))
                clip = true
            }
            // Like the opaque Surface it replaces: nothing under the page takes a touch.
            .pointerInput(Unit) {},
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = morphPageAlpha(fraction()) }
                .then(if (morph.morphing) Modifier.clearAndSetSemantics { } else Modifier),
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
        }
    }
}

/** The panel's hairline border, fading out (by [fade]) as the corners square off (by [rest]). */
internal fun DrawScope.drawFadingHairline(color: Color, rest: Float, fade: Float = rest) {
    if (rest <= 0f || fade <= 0f) return
    val stroke = LettaDimens.Stroke.hairline.toPx()
    val radius = LettaDimens.Radius.lg.toPx() * rest
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius),
        alpha = LettaDimens.Alpha.hairline * fade,
        style = Stroke(stroke),
    )
}

internal fun morphDockedAlpha(fraction: Float): Float =
    (1f - fraction / ChatMotionTokens.SurfaceMorph.CROSSFADE_FRACTION).coerceIn(0f, 1f)

internal fun morphPageAlpha(fraction: Float): Float {
    val share = ChatMotionTokens.SurfaceMorph.CROSSFADE_FRACTION
    return ((fraction - (1f - share)) / share).coerceIn(0f, 1f)
}

internal fun lerpRect(from: ChatDockRect, to: ChatDockRect, fraction: Float): ChatDockRect = ChatDockRect(
    left = lerpFloat(from.left, to.left, fraction),
    top = lerpFloat(from.top, to.top, fraction),
    width = lerpFloat(from.width, to.width, fraction),
    height = lerpFloat(from.height, to.height, fraction),
)

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

internal const val SURFACE_MORPH_TAG = "chat-surface-morph"
