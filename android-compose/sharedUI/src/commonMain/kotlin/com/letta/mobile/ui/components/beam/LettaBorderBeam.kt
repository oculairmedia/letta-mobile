package com.letta.mobile.ui.components.beam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.letta.mobile.ui.theme.tokens.LettaBeamMotion
import com.letta.mobile.ui.theme.tokens.LettaBeamPalette
import com.letta.mobile.ui.theme.tokens.LettaBeamTone
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How a border beam moves. */
enum class LettaBeamStyle {
    /** A comet circling the whole border with a soft inner glow: cards, panels, the composer. */
    Orbit,

    /** A longer, tighter comet for small controls: icon buttons, avatars, chips. */
    OrbitCompact,

    /** A glow sweeping back and forth along the bottom edge: search and input bars. */
    Sweep,
}

/** Which way an orbiting beam travels, judged along the top edge. */
enum class LettaBeamDirection(internal val sign: Float) {
    /** Left to right along the top edge. */
    Clockwise(1f),

    /** Right to left along the top edge. */
    CounterClockwise(-1f),
}

/**
 * What a border beam looks like.
 *
 * @param colors the beam's colours from tail to head, usually [LettaBeamDefaults.colors]; up to eight
 *   are blended (a longer list is thinned evenly) and each colour's alpha fades its stretch of the beam
 * @param shape the outline the beam follows: pass the same shape the container is clipped or filled
 *   with. Rounded corners are followed exactly, each with its own radius (an elliptical corner
 *   follows its smaller radius); any other outline is treated as a plain rectangle.
 * @param style how the beam moves
 * @param direction which way an orbiting beam travels
 */
@Immutable
data class LettaBeamSpec(
    val colors: List<Color>,
    val shape: Shape,
    val style: LettaBeamStyle = LettaBeamStyle.Orbit,
    val direction: LettaBeamDirection = LettaBeamDirection.Clockwise,
)

/** The theme's beam colours. */
object LettaBeamDefaults {
    /** The colours for [tone], tail to head, read from the theme (except [LettaBeamTone.Spectrum]). */
    @Composable
    @ReadOnlyComposable
    fun colors(tone: LettaBeamTone = LettaBeamTone.Working): List<Color> {
        val scheme = MaterialTheme.colorScheme
        return when (tone) {
            LettaBeamTone.Working -> listOf(
                scheme.primary.copy(alpha = LettaBeamPalette.TAIL_ALPHA),
                scheme.primary,
                scheme.tertiary,
            )
            LettaBeamTone.Attention -> single(scheme.error)
            LettaBeamTone.Spectrum -> LettaBeamPalette.spectrum
        }
    }

    /** A one-colour beam that fades in and out along its length. */
    fun single(color: Color): List<Color> {
        val faint = color.copy(alpha = color.alpha * LettaBeamPalette.TAIL_ALPHA)
        return listOf(faint, color, faint)
    }
}

/**
 * Draws an animated beam of light travelling around this element's border, over its content: the
 * signal that something here is live (a run in progress, a stream arriving, a recording).
 *
 *     Surface(shape = shape, modifier = Modifier.lettaBorderBeam(
 *         LettaBeamSpec(LettaBeamDefaults.colors(), shape), active = isRunning,
 *     )) { ... }
 *
 * Drawn by a runtime shader on Android 13+, desktop and web, and by Canvas strokes elsewhere.
 * Switching [active] fades the beam in or out. Its cost, which is zero while it is still:
 * - a graphics layer confines redraws to this element; frames never recompose or relayout anything
 * - the frame loop stops while the beam is off (after its fade), while the element is scrolled or
 *   clipped out of the window, and under reduced motion, where the beam stays lit but still
 *
 * Ported from border-beam-android by ismnoiet (MIT, see sharedUI/THIRD_PARTY_LICENSES).
 */
fun Modifier.lettaBorderBeam(spec: LettaBeamSpec, active: Boolean = true): Modifier =
    graphicsLayer() then BorderBeamElement(spec, active, 1f, 1f)

internal fun Modifier.composerBorderBeam(spec: LettaBeamSpec, active: Boolean): Modifier =
    graphicsLayer() then BorderBeamElement(
        spec, active, LettaComponentDimens.composerBeamWidthScale, LettaComponentDimens.composerBeamIntensity,
    )

private data class BorderBeamElement(val spec: LettaBeamSpec, val active: Boolean, val widthScale: Float, val intensity: Float) :
    ModifierNodeElement<BorderBeamNode>() {
    override fun create(): BorderBeamNode = BorderBeamNode(spec, active, widthScale, intensity)

    override fun update(node: BorderBeamNode) = node.update(spec, active)

    override fun InspectorInfo.inspectableProperties() {
        name = "lettaBorderBeam"
        properties["spec"] = spec
        properties["active"] = active
    }
}

/** The glow reach of each style. */
internal val LettaBeamStyle.glowReach: Dp
    get() = when (this) {
        LettaBeamStyle.Orbit -> LettaComponentDimens.beamGlowOrbit
        LettaBeamStyle.OrbitCompact -> LettaComponentDimens.beamGlowCompact
        LettaBeamStyle.Sweep -> LettaComponentDimens.beamGlowSweep
    }

/** Milliseconds per lap (orbit) or per there-and-back pass (sweep). */
internal val LettaBeamStyle.periodMillis: Int
    get() = if (this == LettaBeamStyle.Sweep) LettaBeamMotion.SWEEP_MILLIS else LettaBeamMotion.LAP_MILLIS

/** Below this the beam counts as faded out and is not drawn. */
private const val VISIBLE_ALPHA = 0.001f

private data class OutlineKey(val size: Size, val density: Float, val fontScale: Float, val direction: LayoutDirection)

internal class BorderBeamNode(spec: LettaBeamSpec, active: Boolean, private val widthScale: Float = 1f, private val intensity: Float = 1f) :
    Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode,
    ObserverModifierNode,
    GlobalPositionAwareModifierNode {

    override val shouldAutoInvalidate: Boolean get() = false

    private var spec = spec
    private var active = active
    private var frame = frameFor(spec)
    private var outlineKey: OutlineKey? = null

    /** Starts anywhere on the lap, so beams side by side do not move in lockstep. */
    private var phase = Random.nextFloat()
    private val fade = Animatable(if (active) 1f else 0f)
    private var reducedMotion = false
    private var onScreen = true
    private var loop: Job? = null
    private var fadeJob: Job? = null

    private var shaderResolved = false
    private var shader: BorderBeamRenderer? = null
    private val fallback = BorderBeamFallback()

    override fun onAttach() {
        readReducedMotion()
        // A fade cut short by a detach resumes toward the current target instead of staying half lit.
        if (fade.value != fadeTarget()) animateFade()
        syncLoop()
    }

    private fun fadeTarget(): Float = if (active) 1f else 0f

    override fun onDetach() {
        loop = null
        fadeJob = null
        shader?.release()
        shader = null
        shaderResolved = false
    }

    override fun onObservedReadsChanged() {
        outlineKey = null
        readReducedMotion()
        syncLoop()
        invalidateDraw()
    }

    private fun readReducedMotion() = observeReads { reducedMotion = currentValueOf(LocalLettaReducedMotion) }

    fun update(spec: LettaBeamSpec, active: Boolean) {
        if (spec != this.spec) {
            this.spec = spec
            frame = frameFor(spec)
            outlineKey = null
            invalidateDraw()
        }
        if (active != this.active) {
            this.active = active
            animateFade()
        }
    }

    private fun animateFade() {
        if (!isAttached) return
        val target = fadeTarget()
        fadeJob?.cancel()
        syncLoop()
        fadeJob = coroutineScope.launch {
            if (reducedMotion) fade.snapTo(target) else fade.animateTo(target, tween(LettaBeamMotion.FADE_MILLIS))
            // A finished fade-out stops the frame loop.
            syncLoop()
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        // Bounds in the window are clipped by every ancestor, so a beam scrolled or clipped out of
        // view has none left.
        val bounds = coordinates.boundsInWindow()
        val visible = bounds.width > 0f && bounds.height > 0f
        if (visible != onScreen) {
            onScreen = visible
            syncLoop()
        }
    }

    /** Runs the frame loop exactly while the beam is attached, visible, moving and not faded out. */
    private fun syncLoop() {
        val shouldRun = isAttached && onScreen && !reducedMotion && (active || fade.value > VISIBLE_ALPHA)
        if (shouldRun == (loop?.isActive == true)) return
        loop?.cancel()
        loop = if (shouldRun) coroutineScope.launch { runFrames() } else null
    }

    private suspend fun runFrames() {
        var last = 0L
        while (coroutineScope.isActive) {
            withInfiniteAnimationFrameNanos { now ->
                if (last != 0L) phase = advanceBeamPhase(phase, now - last, spec.style.periodMillis)
                last = now
            }
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = fade.value
        if (alpha <= VISIBLE_ALPHA || size.minDimension <= 0f) return
        frame.phase = if (reducedMotion) LettaBeamMotion.STILL_PHASE else phase
        frame.alpha = alpha * intensity
        refreshGeometry()
        val renderer = resolveShader(size.width * size.height) ?: fallback
        with(renderer) { drawBeam(frame) }
    }

    private fun ContentDrawScope.refreshGeometry() {
        val key = OutlineKey(size, density, fontScale, layoutDirection)
        if (outlineKey == key) return
        observeReads {
            reducedMotion = currentValueOf(LocalLettaReducedMotion)
            cornersFor(spec.shape, this, frame.cornersPx)
        }
        outlineKey = key
    }

    /** The platform shader, compiled on the first draw and kept until detach; null means use the fallback. */
    private fun resolveShader(areaPx: Float): BorderBeamRenderer? {
        if (!shaderResolved) {
            shaderResolved = true
            shader = createBorderBeamShader(areaPx)
        }
        return shader
    }

    private fun frameFor(spec: LettaBeamSpec) = BeamFrame(spec.style, spec.direction, BeamPalette(spec.colors), widthScale)
}

/**
 * Writes the corner radii the beam follows for [shape] at the scope's size into [out], in px, in
 * [BeamFrame.cornersPx] order and each clamped to half the shorter side: a rounded outline's own
 * corners (the smaller radius of an elliptical one), all 0 for a rectangle or any other outline.
 * The outline is built for the scope's layout direction, so a right-to-left shape mirrors.
 */
internal fun cornersFor(shape: Shape, scope: DrawScope, out: FloatArray) {
    val size = scope.size
    val limit = min(size.width, size.height) / 2f
    val outline = shape.createOutline(size, scope.layoutDirection, scope)
    if (outline !is Outline.Rounded) {
        out.fill(0f)
        return
    }
    with(outline.roundRect) {
        out[0] = topRightCornerRadius.circular(limit)
        out[1] = bottomRightCornerRadius.circular(limit)
        out[2] = bottomLeftCornerRadius.circular(limit)
        out[3] = topLeftCornerRadius.circular(limit)
    }
}

private fun CornerRadius.circular(limit: Float): Float = min(x, y).coerceIn(0f, limit)
