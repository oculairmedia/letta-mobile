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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.session.ChatDockRect
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * letta-mobile-bglj6.1: the docked panel grows into the full-screen page, and shrinks back, as
 * one continuous transform instead of a jump. One progress (0 docked .. 1 full screen) drives a
 * single container: its rect goes from the panel's bounds to the whole area, its corners square
 * off, its shadow drops away and its fill turns from the panel's surface into the page
 * background, which also fades in over the canvas. Inside it the panel's own content fades out
 * as the page's fades in. At rest nothing of this is composed: the docked panel and the page
 * draw exactly as they always did.
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

/** What the morph cross-fades: the docked panel's inside and the full page's. */
@Immutable
internal class SurfaceMorphContent(
    val docked: @Composable () -> Unit,
    val page: @Composable () -> Unit,
)

/**
 * The transform itself, over the whole area. It starts from (or lands on) the rect [dock]
 * places the panel at, so the hand-off to the panel at rest does not move a pixel. Hidden from
 * accessibility: it is only ever on screen for a moment.
 */
@Composable
internal fun SurfaceMorphLayer(
    progress: Animatable<Float, AnimationVector1D>,
    dock: ChatDockState,
    content: SurfaceMorphContent,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // The panel rises above the keyboard (DockedChatPanel pads for it); the page does not.
    val imeDp = with(density) { WindowInsets.ime.getBottom(density).toDp().value }
    val fraction = { progress.value }
    // Plain layout, no BoxWithConstraints: the page's effects must not start mid-measure.
    Box(modifier.fillMaxSize()) {
        MorphBackdrop(fraction)
        MorphContainer(MorphGeometry(dock, imeDp, fraction)) { MorphCrossFade(fraction, content) }
    }
}

/** Where the container is at the current progress, resolved against the area at layout time. */
private class MorphGeometry(val dock: ChatDockState, val imeDp: Float, val fraction: () -> Float) {
    fun rectIn(widthDp: Float, heightDp: Float): ChatDockRect {
        val from = dock.rectIn(widthDp, (heightDp - imeDp).coerceAtLeast(0f))
        val to = ChatDockRect(0f, 0f, widthDp, heightDp)
        return lerpRect(from, to, fraction())
    }
}

/** The page background fading in over the canvas. */
@Composable
private fun MorphBackdrop(fraction: () -> Float) {
    val color = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().drawBehind { drawRect(color, alpha = fraction()) })
}

@Composable
private fun MorphContainer(geometry: MorphGeometry, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val panel = scheme.surfaceContainer
    val page = scheme.background
    val outline = scheme.outlineVariant
    val fraction = geometry.fraction
    Box(
        Modifier
            .morphBounds(geometry)
            .testTag(SURFACE_MORPH_TAG)
            .graphicsLayer {
                val rest = 1f - fraction()
                // Neutral fill plus shadow, as the panel at rest: no tonal elevation tint.
                shadowElevation = ChatSurfaceDimens.dockedReplyElevation.toPx() * rest
                shape = RoundedCornerShape(LettaDimens.Radius.lg.toPx() * rest)
                clip = true
            }
            .drawBehind { drawRect(lerp(panel, page, fraction())) }
            .drawWithContent {
                drawContent()
                drawFadingHairline(outline, 1f - fraction())
            },
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) { content() }
    }
}

/** The panel's hairline border, fading out as the corners square off. */
private fun DrawScope.drawFadingHairline(color: Color, rest: Float) {
    if (rest <= 0f) return
    val stroke = LettaDimens.Stroke.hairline.toPx()
    val radius = LettaDimens.Radius.lg.toPx() * rest
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius),
        alpha = LettaDimens.Alpha.hairline * rest,
        style = Stroke(stroke),
    )
}

/** Panel content out, page content in; they overlap in the middle so nothing pops. */
@Composable
private fun MorphCrossFade(fraction: () -> Float, content: SurfaceMorphContent) {
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = morphDockedAlpha(fraction()) }) { content.docked() }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = morphPageAlpha(fraction()) }) { content.page() }
    }
}

internal fun morphDockedAlpha(fraction: Float): Float =
    (1f - fraction / ChatMotionTokens.SurfaceMorph.CROSSFADE_FRACTION).coerceIn(0f, 1f)

internal fun morphPageAlpha(fraction: Float): Float {
    val share = ChatMotionTokens.SurfaceMorph.CROSSFADE_FRACTION
    return ((fraction - (1f - share)) / share).coerceIn(0f, 1f)
}

/**
 * Takes the whole area its parent offers and places the container inside it at the current
 * rect. Modifiers after this one (the tag, the shape, the fill) see only the morphing rect.
 * Reads the progress at layout time, so a frame relayouts without recomposing.
 */
private fun Modifier.morphBounds(geometry: MorphGeometry): Modifier = layout { measurable, constraints ->
    val rect = geometry.rectIn(constraints.maxWidth.toDp().value, constraints.maxHeight.toDp().value)
    val width = rect.width.dp.roundToPx().coerceAtLeast(0)
    val height = rect.height.dp.roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place(rect.left.dp.roundToPx(), rect.top.dp.roundToPx())
    }
}

internal fun lerpRect(from: ChatDockRect, to: ChatDockRect, fraction: Float): ChatDockRect = ChatDockRect(
    left = lerpFloat(from.left, to.left, fraction),
    top = lerpFloat(from.top, to.top, fraction),
    width = lerpFloat(from.width, to.width, fraction),
    height = lerpFloat(from.height, to.height, fraction),
)

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction

internal const val SURFACE_MORPH_TAG = "chat-surface-morph"
