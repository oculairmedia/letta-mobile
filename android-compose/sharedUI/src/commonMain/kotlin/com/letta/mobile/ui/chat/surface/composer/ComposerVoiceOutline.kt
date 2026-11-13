package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.ambient.AmbientVoice
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

/** Whose colour voice mode's outline shows on a prompt surface (tests read it; pixels cannot be asserted). */
internal val VoiceOutlineRoleKey = SemanticsPropertyKey<VoiceGlowRole>("VoiceOutlineRole")
internal var SemanticsPropertyReceiver.voiceOutlineRole by VoiceOutlineRoleKey

/** Whether the outline moves with a voice (false under reduced motion and while it holds still). */
internal val VoiceOutlineAnimatedKey = SemanticsPropertyKey<Boolean>("VoiceOutlineAnimated")
internal var SemanticsPropertyReceiver.voiceOutlineAnimated by VoiceOutlineAnimatedKey

/**
 * Voice mode's glowing outline around a prompt surface drawn in [shape]: a soft glow and a brighter
 * thin edge along the surface's own border, in the colour of whoever is active ([voiceGlowRole]: the
 * person's amber while listening and hearing, the agent's thinking colour while it thinks and speaks,
 * the error colour after a failure, nothing otherwise). Its brightness follows the same level the
 * page's ambient glow follows (resting while nobody speaks), so the two move together; the colour
 * cross-fades over the glow's glide.
 *
 * It only draws, behind the surface (the surface's own fill hides the inner half of each stroke), so
 * no layout bounds change and nothing it covers loses a click; the glow bleeds a few dp past the
 * surface. It animates on the frame clock only while it follows a voice; under reduced motion it is a
 * still edge at a fixed brightness. Without voice mode on the page it is nothing.
 */
@Composable
internal fun Modifier.voiceOutline(shape: Shape): Modifier {
    val handle = LocalVoiceMode.current ?: return this
    val state by handle.state.collectAsState()
    val role = voiceGlowRole(state)
    val reducedMotion = LocalReducedMotion.current
    val target = when (role) {
        VoiceGlowRole.You -> voiceYouEdgeColor()
        VoiceGlowRole.Agent -> voiceAgentColor()
        VoiceGlowRole.Error -> MaterialTheme.colorScheme.error
        VoiceGlowRole.Idle -> Color.Transparent
    }
    val color = animateColorAsState(
        targetValue = target,
        animationSpec = if (reducedMotion) snap() else tween(ChatMotionTokens.AmbientGlow.GLIDE_MILLIS),
        label = "voiceOutlineColor",
    )
    val level = handle.glowLevel(state)
    val animated = !reducedMotion && level != null
    val currentLevel by rememberUpdatedState(level)
    val brightness = remember { mutableFloatStateOf(STILL_BRIGHTNESS) }
    LaunchedEffect(animated) {
        if (!animated) {
            brightness.floatValue = STILL_BRIGHTNESS
            return@LaunchedEffect
        }
        // An infinite animation: it is the outline's clock, not work a test should wait on.
        while (true) {
            withInfiniteAnimationFrameNanos {
                val reading = currentLevel?.invoke() ?: 0f
                val wanted = AmbientVoice.RESTING_SHARE + (1f - AmbientVoice.RESTING_SHARE) * reading.coerceIn(0f, 1f)
                brightness.floatValue += (wanted - brightness.floatValue) * FOLLOW
            }
        }
    }
    return this
        .semantics {
            voiceOutlineRole = role
            voiceOutlineAnimated = animated
        }
        .drawWithCache {
            val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
            val glow = Stroke(width = LettaDimens.Space.md.toPx())
            val halo = Stroke(width = LettaDimens.Space.sm.toPx())
            val edge = Stroke(width = LettaDimens.Space.xs.toPx())
            onDrawBehind {
                val tint = color.value
                if (tint.alpha <= HIDDEN_ALPHA) return@onDrawBehind
                val lit = brightness.floatValue
                drawPath(path, tint.copy(alpha = tint.alpha * GLOW_ALPHA * lit), style = glow)
                drawPath(path, tint.copy(alpha = tint.alpha * HALO_ALPHA * lit), style = halo)
                drawPath(path, tint.copy(alpha = tint.alpha * (EDGE_FLOOR + (1f - EDGE_FLOOR) * lit)), style = edge)
            }
        }
}

/** The fixed brightness of a still outline: reduced motion, the thinking state, a failure. */
private const val STILL_BRIGHTNESS = 0.6f

/** Share of the way to the level the brightness moves each frame: a short glide, no flicker. */
private const val FOLLOW = 0.25f
private const val GLOW_ALPHA = 0.22f
private const val HALO_ALPHA = 0.4f
private const val EDGE_FLOOR = 0.55f
private const val HIDDEN_ALPHA = 0.001f
