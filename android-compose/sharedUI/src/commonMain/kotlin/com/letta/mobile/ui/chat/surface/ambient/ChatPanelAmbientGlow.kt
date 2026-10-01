package com.letta.mobile.ui.chat.surface.ambient

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.ambient.AmbientBand
import com.letta.mobile.ui.ambient.AmbientMotion
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import com.letta.mobile.ui.ambient.VisibleAssistantStreamPulseState
import com.letta.mobile.ui.ambient.reduceVisibleAssistantStreamPulse
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/*
 * letta-mobile-bglj6.1: the canvas chat window's thinking cue. While the agent works, the docked
 * panel (and the minimised dock around its mascot) glows with the same ambient shader the hosts
 * draw behind the full chat page: Android's AmbientShaderAgentBackground (AGSL) and desktop's
 * DesktopAmbientChatBackground (Skia). The shader source and the status -> motion table are the
 * shared ones in sharedLogic (AMBIENT_GLOW_SHADER_SOURCE, AmbientMotion); only compiling and
 * drawing it is per platform (rememberAmbientGlowShader).
 *
 * It replaces the window's own animated working cues (the run header's pulsing orb, the
 * reasoning spinner, the streaming progress bar and the minimised dock's thinking dots), which
 * the window turns off through LocalChatWorkingCueAnimated.
 */

/**
 * Whether chat rows animate their own working cue: the run header's pulsing orb and the
 * reasoning header's spinner. The docked panel turns it off; its ambient glow says the agent is
 * working instead. The rows keep their text ("Working", "Thinking...") either way.
 */
internal val LocalChatWorkingCueAnimated: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/** The agent's activity as the glow shows it, and the visible-stream pulse that livens it. */
@Immutable
internal data class ChatAmbient(val status: AmbientMotionStatus, val streamPulse: Long = 0L) {
    companion object {
        val Idle = ChatAmbient(AmbientMotionStatus.Idle)
    }
}

/**
 * The page's ambient status from [state], mapped the way both hosts map theirs (desktop
 * rememberDesktopAmbientStatus, Android's ChatScreen ambient effect): an error fails, a run in
 * flight (typing or streaming) runs, the end of a run blooms Completed and holds for exactly the
 * shared decay before going Idle.
 */
@Composable
internal fun rememberChatAmbient(state: ChatUiState): ChatAmbient {
    val status = rememberChatAmbientStatus(isThinking = state.isAgentTyping || state.isStreaming, error = state.error)
    val pulse = rememberVisibleStreamPulse(state)
    return remember(status, pulse) { ChatAmbient(status, pulse) }
}

@Composable
internal fun rememberChatAmbientStatus(isThinking: Boolean, error: String?): AmbientMotionStatus {
    var status by remember { mutableStateOf(AmbientMotionStatus.Idle) }
    var hadActiveRun by remember { mutableStateOf(false) }
    LaunchedEffect(isThinking, error) {
        when {
            error != null -> status = AmbientMotionStatus.Failed
            isThinking -> {
                hadActiveRun = true
                status = AmbientMotionStatus.Running
            }
            hadActiveRun -> {
                status = AmbientMotionStatus.Completed
                // A shorter hold cuts the Completed decay off and Idle animates the envelope
                // back up: a rebound instead of an afterglow (see AmbientMotion.holdMillis).
                delay(AmbientMotion.holdMillis(AmbientMotionStatus.Completed).milliseconds)
                hadActiveRun = false
                status = AmbientMotionStatus.Idle
            }
            else -> status = AmbientMotionStatus.Idle
        }
    }
    return status
}

/** Counts visible growth of the streaming assistant reply (the shared reducer the hosts use). */
@Composable
private fun rememberVisibleStreamPulse(state: ChatUiState): Long {
    val tail = if (state.isStreaming) state.messages.lastOrNull { it.role == "assistant" } else null
    var pulse by remember { mutableStateOf(VisibleAssistantStreamPulseState()) }
    LaunchedEffect(state.isStreaming, tail?.id, tail?.content?.length) {
        pulse = reduceVisibleAssistantStreamPulse(pulse, state.isStreaming, tail?.id, tail?.content?.length ?: 0)
    }
    return pulse.pulse
}

/** Where the glow sits in its box. */
@Immutable
internal sealed interface AmbientGlowPlacement {
    /**
     * Inside the docked panel: the light rises from just above its composer bar
     * ([composerHeight], read at draw time) into the conversation, as the phone page's glow
     * rises above its composer.
     */
    @Immutable
    class AboveComposer(val composerHeight: () -> Dp) : AmbientGlowPlacement

    /**
     * Around the minimised dock's mascot and bubble: the glow bleeds past them by
     * [ChatSurfaceDimens.collapsedHaloBleed] and fades out at its edges, a soft halo on the canvas.
     */
    data object Halo : AmbientGlowPlacement
}

/**
 * The ambient thinking glow for [ambient], filling [modifier]'s box. Idle draws nothing (and
 * composes nothing): the glow appears as the agent starts working, takes the error tint when the
 * run fails and decays to nothing after the run.
 *
 * One shader layer, drawn in the draw phase only: its time uniform advances on the frame clock
 * without recomposing. Under reduced motion it holds still: the status tint, no breath, no
 * drift, no bloom.
 */
@Composable
internal fun ChatPanelAmbientGlow(
    ambient: ChatAmbient,
    placement: AmbientGlowPlacement,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val status = ambient.status
    val spec = AmbientMotion.spec(status)
    val glide = if (reducedMotion) 0 else ChatMotionTokens.AmbientGlow.GLIDE_MILLIS
    val tint = animateColorAsState(
        targetValue = ambientTint(status, MaterialTheme.colorScheme),
        animationSpec = tween(durationMillis = glide, easing = EaseInOutCubic),
        label = "chatAmbientTint",
    )
    val speed = animateFloatAsState(spec.speed, tween(durationMillis = glide), label = "chatAmbientSpeed")
    // Zeroed under reduced motion: "animate to no animation" is still animation.
    val agitation = animateFloatAsState(
        targetValue = if (reducedMotion) 0f else spec.agitation,
        animationSpec = tween(durationMillis = glide),
        label = "chatAmbientAgitation",
    )
    val envelope = remember { Animatable(spec.settledEnvelope) }
    LaunchedEffect(status, reducedMotion) {
        // The shared ramp: climb into a bloom, never step into one (AmbientMotion.ramp).
        val ramp = AmbientMotion.ramp(current = envelope.value, status = status)
        if (reducedMotion) {
            envelope.snapTo(ramp.settledEnvelope)
            return@LaunchedEffect
        }
        if (ramp.risesFirst) {
            envelope.animateTo(ramp.bloomEnvelope, tween(durationMillis = ramp.riseMillis, easing = EaseInOutCubic))
        }
        envelope.animateTo(ramp.settledEnvelope, tween(durationMillis = ramp.settleMillis, easing = EaseOutCubic))
    }
    val visible by remember(tint) { derivedStateOf { tint.value.alpha > HIDDEN_ALPHA } }
    if (!visible) return
    GlowLayer(
        inputs = GlowInputs(
            tint = tint,
            speed = speed,
            agitation = agitation,
            envelope = envelope.asState(),
            streamPulse = ambient.streamPulse,
        ),
        animate = !reducedMotion,
        placement = placement,
        modifier = modifier,
    )
}

/** The glow's animated inputs, read at draw time. */
@Stable
private class GlowInputs(
    val tint: State<Color>,
    val speed: State<Float>,
    val agitation: State<Float>,
    val envelope: State<Float>,
    val streamPulse: Long,
)

@Composable
private fun GlowLayer(inputs: GlowInputs, animate: Boolean, placement: AmbientGlowPlacement, modifier: Modifier) {
    val motion = rememberGlowMotion(animate, inputs.speed, inputs.streamPulse)
    val shader = rememberAmbientGlowShader()
    val settledFrames = rememberSettledRedraw(inputs, motion)
    Spacer(
        modifier
            .then(if (placement is AmbientGlowPlacement.Halo) Modifier.bleed(ChatSurfaceDimens.collapsedHaloBleed) else Modifier)
            .testTag(CHAT_AMBIENT_GLOW_TAG)
            .semantics { ambientGlowAnimated = animate }
            // Offscreen so the halo's feathering masks only the glow, not the canvas under it.
            .graphicsLayer {
                if (placement is AmbientGlowPlacement.Halo) compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawBehind {
                // Read so the settled redraw below reaches this draw.
                settledFrames.intValue
                val uniforms = AmbientGlowUniforms(
                    phase = motion.phase.floatValue,
                    agitation = inputs.agitation.value,
                    envelope = inputs.envelope.value,
                    streamEnergy = motion.streamEnergy.floatValue,
                    band = if (placement is AmbientGlowPlacement.Halo) HALO_BAND else PANEL_BAND,
                    tint = inputs.tint.value,
                    fieldHeight = fieldHeightFor(placement),
                    gain = GLOW_GAIN,
                )
                if (shader != null) with(shader) { drawGlow(uniforms) } else drawAmbientGlowFallback(uniforms)
                if (placement is AmbientGlowPlacement.Halo) featherEdges()
            },
    )
}

/**
 * One extra redraw once the glow's inputs have held still for a moment (reduced motion, a
 * settled status with its clock paused). A renderer that records the draw and replays it
 * (desktop's Skia pictures) would otherwise replay the shader itself, per pixel, on every frame
 * of the window; redrawn with unchanged inputs, the desktop shader draws a still image of itself
 * instead. While the glow moves its inputs change every frame and this never fires.
 */
@Composable
private fun rememberSettledRedraw(inputs: GlowInputs, motion: GlowMotion): MutableIntState {
    val settled = remember { mutableIntStateOf(0) }
    val current by rememberUpdatedState(inputs)
    LaunchedEffect(motion) {
        snapshotFlow {
            listOf(
                current.tint.value,
                current.agitation.value,
                current.envelope.value,
                motion.phase.floatValue,
                motion.streamEnergy.floatValue,
            )
        }.collectLatest {
            delay(SETTLED_REDRAW_DELAY_MILLIS)
            settled.intValue++
        }
    }
    return settled
}

/**
 * How tall the shader's field is. The shader's light lives along the bottom edge of its field;
 * in the panel that edge is moved up to just above the composer bar, so the light rises from the
 * bar into the conversation instead of hiding behind it.
 */
private fun DrawScope.fieldHeightFor(placement: AmbientGlowPlacement): Float = when (placement) {
    is AmbientGlowPlacement.AboveComposer -> {
        val composer = placement.composerHeight()
        val composerPx = if (composer == Dp.Unspecified) 0f else composer.toPx()
        (size.height - composerPx + PanelFieldOverlap.toPx()).coerceIn(1f, size.height)
    }
    // The halo's light rises from the bar the mascot stands on: the field ends at the row's foot,
    // not at the bottom of the bleed below it (which the bar covers).
    AmbientGlowPlacement.Halo -> (size.height - ChatSurfaceDimens.collapsedHaloBleed.toPx()).coerceAtLeast(1f)
}

/** Fades the halo to nothing at its sides and top, so it has no edge on the canvas. */
private fun DrawScope.featherEdges() {
    val clear = Color.Transparent
    val solid = Color.Black
    drawRect(
        brush = Brush.horizontalGradient(
            0f to clear,
            HALO_FEATHER to solid,
            1f - HALO_FEATHER to solid,
            1f to clear,
        ),
        blendMode = BlendMode.DstIn,
    )
    drawRect(
        brush = Brush.verticalGradient(0f to clear, HALO_FEATHER to solid, 1f to solid),
        blendMode = BlendMode.DstIn,
    )
}

/** Lays the box out [amount] larger on every side than its slot, centred on it. */
private fun Modifier.bleed(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
    val placeable = measurable.measure(Constraints.fixed(width + 2 * extra, height + 2 * extra))
    layout(width, height) { placeable.place(-extra, -extra) }
}

/** The speed-integrated phase (turns, wrapped) and the decaying stream energy. */
@Stable
private class GlowMotion {
    val phase = mutableFloatStateOf(0f)
    val streamEnergy = mutableFloatStateOf(0f)
}

@Composable
private fun rememberGlowMotion(animate: Boolean, speed: State<Float>, streamPulse: Long): GlowMotion {
    val motion = remember { GlowMotion() }
    val pulse by rememberUpdatedState(streamPulse)
    LaunchedEffect(animate) {
        if (!animate) {
            motion.streamEnergy.floatValue = 0f
            return@LaunchedEffect
        }
        var last = 0L
        var observed = pulse
        // An infinite animation: it is the glow's clock, not work a test should wait on.
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / NANOS_PER_SECOND).coerceIn(0f, ChatMotionTokens.AmbientGlow.MAX_FRAME_DELTA_SECONDS)
                    var energy = motion.streamEnergy.floatValue
                    if (pulse != observed) energy = (energy + ChatMotionTokens.AmbientGlow.STREAM_IMPULSE).coerceAtMost(1f)
                    observed = pulse
                    motion.streamEnergy.floatValue = energy * exp(-dt / ChatMotionTokens.AmbientGlow.STREAM_ENERGY_DECAY_SECONDS)
                    // Turns, wrapped: an unbounded phase loses float resolution and eventually
                    // judders (AmbientMotion.PHASE_WRAP_TURNS).
                    motion.phase.floatValue = (motion.phase.floatValue + dt * speed.value) % AmbientMotion.PHASE_WRAP_TURNS
                }
                last = now
            }
        }
    }
    return motion
}

/**
 * The hosts' status tints (desktop DesktopAmbientChatBackground): working in the tertiary role,
 * failure in the error role (the alarm channel survives every theme), the end of a run in
 * secondary, and nothing at rest.
 */
internal fun ambientTint(status: AmbientMotionStatus, scheme: ColorScheme): Color = when (status) {
    AmbientMotionStatus.Idle -> Color.Transparent
    AmbientMotionStatus.Running, AmbientMotionStatus.Active -> scheme.tertiary
    AmbientMotionStatus.Failed -> scheme.error
    AmbientMotionStatus.Completed -> scheme.secondary
}

/** One frame's uniforms for the shared ambient shader (AMBIENT_GLOW_SHADER_SOURCE's contract). */
@Immutable
internal data class AmbientGlowUniforms(
    val phase: Float,
    val agitation: Float,
    val envelope: Float,
    val streamEnergy: Float,
    val band: AmbientBand,
    val tint: Color,
    /** The field's height in px (the shader's uSize.y); the glow is drawn over the whole scope. */
    val fieldHeight: Float,
    /** Multiplies the tint's alpha (the shader's uColor.a), which scales the whole effect. */
    val gain: Float,
)

/** The platform's compiled ambient shader, drawing one frame over the whole draw scope. */
internal interface AmbientGlowShader {
    fun DrawScope.drawGlow(uniforms: AmbientGlowUniforms)
}

/**
 * The shared ambient shader compiled for this platform (Skia RuntimeEffect on desktop, AGSL
 * RuntimeShader on Android 13+), or null where it cannot run; the gradient fallback draws then.
 */
@Composable
internal expect fun rememberAmbientGlowShader(): AmbientGlowShader?

/**
 * The hosts' non-shader fallback: a radial gradient anchored at the bottom edge, breathing on the
 * same phase, agitation and envelope floats as the shader so the two stay in step.
 */
internal fun DrawScope.drawAmbientGlowFallback(uniforms: AmbientGlowUniforms) {
    val tint = uniforms.tint
    val breath = 0.5f + 0.5f * sin(TWO_PI * FALLBACK_BREATH_RATE * uniforms.phase)
    val wobble = sin(TWO_PI * FALLBACK_WOBBLE_RATE * uniforms.phase) * FALLBACK_WOBBLE * uniforms.agitation
    val radius = size.maxDimension * (FALLBACK_RADIUS + FALLBACK_BREATH_RADIUS * breath + wobble)
    val intensity = uniforms.envelope * uniforms.gain
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to tint.copy(alpha = tint.alpha * (FALLBACK_CORE_ALPHA + FALLBACK_STREAM_ALPHA * uniforms.streamEnergy) * intensity),
                FALLBACK_MID_STOP to tint.copy(alpha = tint.alpha * FALLBACK_MID_ALPHA * intensity),
                1f to tint.copy(alpha = 0f),
            ),
            center = Offset(size.width * 0.5f, uniforms.fieldHeight * FALLBACK_ANCHOR_Y),
            radius = radius,
        ),
    )
}

internal const val AMBIENT_GLOW_TELEMETRY_TAG = "ChatAmbientGlow"

/** Tags the glow while it is drawn; absent while the agent is idle. */
internal const val CHAT_AMBIENT_GLOW_TAG = "chat-ambient-glow"

/** Whether the glow is moving (false under reduced motion: a still tint). */
internal val AmbientGlowAnimatedKey = SemanticsPropertyKey<Boolean>("AmbientGlowAnimated")
internal var SemanticsPropertyReceiver.ambientGlowAnimated by AmbientGlowAnimatedKey

/** The halo is short: it lifts its glow over most of its height rather than a thin edge strip. */
private val HALO_BAND = AmbientBand(top = 0.05f, peak = 0.8f)

/**
 * The panel's band: taller than a page's, since a panel a third of the window high would
 * otherwise show the glow as a sliver along its bar.
 */
private val PANEL_BAND = AmbientBand(top = 0.45f, peak = 0.97f)

/** How far the panel's field reaches down behind the composer bar's top edge. */
private val PanelFieldOverlap = 12.dp

/**
 * The shader's strength is tuned for a whole window (BAND_OPACITY), where the glow spans the
 * pane; inside a panel or around the mascot it covers far less, so it is lifted to read as the
 * thinking cue. The shader still clamps the result's alpha (0.78).
 */
private const val GLOW_GAIN = 2.4f

/** Share of the halo's width (and height) its edges take to fade out. */
private const val HALO_FEATHER = 0.22f

private const val HIDDEN_ALPHA = 0.001f
private const val SETTLED_REDRAW_DELAY_MILLIS = 120L
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val TWO_PI = (2 * PI).toFloat()
private const val FALLBACK_BREATH_RATE = 0.0146f
private const val FALLBACK_WOBBLE_RATE = 0.0394f
private const val FALLBACK_WOBBLE = 0.03f
private const val FALLBACK_RADIUS = 0.46f
private const val FALLBACK_BREATH_RADIUS = 0.12f
private const val FALLBACK_CORE_ALPHA = 0.18f
private const val FALLBACK_STREAM_ALPHA = 0.01f
private const val FALLBACK_MID_STOP = 0.52f
private const val FALLBACK_MID_ALPHA = 0.08f
private const val FALLBACK_ANCHOR_Y = 0.985f
