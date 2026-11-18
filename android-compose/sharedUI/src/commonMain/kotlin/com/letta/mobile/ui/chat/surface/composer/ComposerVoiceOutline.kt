package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import com.letta.mobile.ui.ambient.AmbientVoice
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.components.beam.LettaBeamDefaults
import com.letta.mobile.ui.components.beam.LettaBeamStyle
import com.letta.mobile.ui.components.beam.LettaBeamSpec
import com.letta.mobile.ui.components.beam.composerBorderBeam
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

/** Whose colour voice mode's outline shows on a prompt surface (tests read it; pixels cannot be asserted). */
internal val VoiceOutlineRoleKey = SemanticsPropertyKey<VoiceGlowRole>("VoiceOutlineRole")
internal var SemanticsPropertyReceiver.voiceOutlineRole by VoiceOutlineRoleKey

/** Whether the outline moves with a voice (false under reduced motion and while it holds still). */
internal val VoiceOutlineAnimatedKey = SemanticsPropertyKey<Boolean>("VoiceOutlineAnimated")
internal var SemanticsPropertyReceiver.voiceOutlineAnimated by VoiceOutlineAnimatedKey

/** Semantic outline mode exposed for accessibility and tests. */
internal val ComposerOutlineModeKey = SemanticsPropertyKey<ComposerOutlineMode>("ComposerOutlineMode")
internal var SemanticsPropertyReceiver.composerOutlineMode by ComposerOutlineModeKey

/**
 * Draws the conversation's current outline on its prompt surface. Listening and errors use
 * the voice glow, thinking uses a thin still edge, and working uses the animated beam.
 * Colours glide between states and reduced motion holds the outline still.
 */
@Composable
internal fun Modifier.composerOutline(shape: Shape, uiState: ChatUiState): Modifier {
    val handle = LocalVoiceMode.current
    val state = handle?.state?.collectAsState()?.value
    val role = state?.let(::voiceGlowRole) ?: VoiceGlowRole.Idle
    val presence = LocalMascotRegistry.current.presence[uiState.agentId]
    val mode = composerOutlineMode(state, uiState.runPhase, companionWorking(uiState, presence))
    val reducedMotion = LocalReducedMotion.current
    val targetColor = when (mode) {
        ComposerOutlineMode.Listening, ComposerOutlineMode.Error -> outlineColor(role)
        ComposerOutlineMode.Thinking -> voiceAgentColor().copy(alpha = LettaDimens.Alpha.composerThinking)
        ComposerOutlineMode.Idle, ComposerOutlineMode.Working -> Color.Transparent
    }
    val color = animateColorAsState(
        targetValue = targetColor,
        animationSpec = if (reducedMotion) snap() else tween(ChatMotionTokens.AmbientGlow.GLIDE_MILLIS),
        label = "composerOutlineColor",
    )
    val level = state?.let { handle?.glowLevel(it) }.takeIf { mode == ComposerOutlineMode.Listening && !reducedMotion }
    val brightness = rememberOutlineBrightness(level)
    val colors = LettaBeamDefaults.colors()
    val beam = remember(colors, shape) { LettaBeamSpec(colors, shape, LettaBeamStyle.SweepTop) }
    val softness = animateFloatAsState(
        targetValue = if (mode == ComposerOutlineMode.Thinking) 0f else 1f,
        animationSpec = if (reducedMotion) snap() else tween(ChatMotionTokens.AmbientGlow.GLIDE_MILLIS),
        label = "composerOutlineSoftness",
    )
    return this
        .semantics {
            composerOutlineMode = mode
            voiceOutlineRole = role
            voiceOutlineAnimated = level != null
        }
        .composerBorderBeam(beam, active = mode == ComposerOutlineMode.Working)
        .drawWithCache {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val strokes = OutlineStrokes(this)
            onDrawBehind {
                drawComposerOutline(path, strokes, color.value, OutlineLight(brightness.floatValue, softness.value, mode))
            }
        }
}


private fun topOnly(mode: ComposerOutlineMode, softness: Float): Boolean =
    mode == ComposerOutlineMode.Thinking || (mode == ComposerOutlineMode.Idle && softness < 1f)

private fun DrawScope.drawComposerOutline(
    path: Path, strokes: OutlineStrokes, tint: Color, light: OutlineLight,
) {
    if (topOnly(light.mode, light.softness)) {
        clipRect(bottom = strokes.edge.width) { drawOutline(path, strokes, tint, light) }
    } else {
        drawOutline(path, strokes, tint, light)
    }
}

/** The outline's colour for [role]: the person's, the agent's, the error colour, or none. */
@Composable
private fun outlineColor(role: VoiceGlowRole): Color = when (role) {
    VoiceGlowRole.You -> voiceYouEdgeColor()
    VoiceGlowRole.Agent -> voiceAgentColor()
    VoiceGlowRole.Error -> MaterialTheme.colorScheme.error
    VoiceGlowRole.Idle -> Color.Transparent
}

/**
 * The outline's brightness, 0..1: following [level] on the frame clock (resting at the glow's resting
 * share in silence), or still at [STILL_BRIGHTNESS] when there is no level to follow.
 */
@Composable
private fun rememberOutlineBrightness(level: (() -> Float)?): MutableFloatState {
    val brightness = remember { mutableFloatStateOf(STILL_BRIGHTNESS) }
    val currentLevel by rememberUpdatedState(level)
    val animated = level != null
    LaunchedEffect(animated) {
        if (!animated) {
            brightness.floatValue = STILL_BRIGHTNESS
            return@LaunchedEffect
        }
        // An infinite animation: it is the outline's clock, not work a test should wait on.
        while (true) {
            withInfiniteAnimationFrameNanos {
                val reading = (currentLevel?.invoke() ?: 0f).coerceIn(0f, 1f)
                val wanted = AmbientVoice.RESTING_SHARE + (1f - AmbientVoice.RESTING_SHARE) * reading
                brightness.floatValue += (wanted - brightness.floatValue) * FOLLOW
            }
        }
    }
    return brightness
}

/** The outline's three strokes at the current density: the wide glow, the halo and the thin edge. */
private class OutlineStrokes(density: Density) {
    val glow = Stroke(width = with(density) { LettaDimens.Space.md.toPx() })
    val halo = Stroke(width = with(density) { LettaDimens.Space.sm.toPx() })
    val edge = Stroke(width = with(density) { LettaDimens.Space.xs.toPx() })
}

private data class OutlineLight(val lit: Float, val softness: Float, val mode: ComposerOutlineMode)

/** Draws [path]'s glow, halo and edge in [tint] with [light] brightness; nothing for a transparent tint. */
private fun DrawScope.drawOutline(path: Path, strokes: OutlineStrokes, tint: Color, light: OutlineLight) {
    if (tint.alpha <= HIDDEN_ALPHA) return
    drawPath(path, tint.copy(alpha = tint.alpha * GLOW_ALPHA * light.lit * light.softness), style = strokes.glow)
    drawPath(path, tint.copy(alpha = tint.alpha * HALO_ALPHA * light.lit * light.softness), style = strokes.halo)
    drawPath(path, tint.copy(alpha = tint.alpha * (1f - light.softness * (1f - EDGE_FLOOR) * (1f - light.lit))), style = strokes.edge)
}

/** The fixed brightness of a still outline: reduced motion, the thinking state, a failure. */
private const val STILL_BRIGHTNESS = 0.6f

/** Share of the way to the level the brightness moves each frame: a short glide, no flicker. */
private const val FOLLOW = 0.25f
private const val GLOW_ALPHA = 0.22f
private const val HALO_ALPHA = 0.4f
private const val EDGE_FLOOR = 0.55f
private const val HIDDEN_ALPHA = 0.001f
