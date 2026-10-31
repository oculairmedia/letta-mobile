package com.letta.mobile.ui.shell.rail

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.letta.mobile.ui.chat.agentOrbBrush
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.MASCOT_TILE_OVERSCALE
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion

/**
 * The thinking indicator, drawn BEHIND an agent's tile: the character's own silhouette (its captured
 * still, or the gradient orb's rounded square) in its own colour, blurred into a glow that breathes.
 * Each mascot glows in its shape, and nothing is drawn over its face. Reduced motion holds the glow.
 */
@Composable
fun ShellThinkingAura(
    entry: ShellRailEntry,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = LettaDimens.Radius.sm,
) {
    val breath = auraBreath()
    // Read in the layer, not in composition: the breath redraws the glow without recomposing the rail.
    val glow = Modifier
        .graphicsLayer {
            val scale = lerp(SCALE_REST, SCALE_PEAK, breath.value)
            scaleX = scale
            scaleY = scale
            alpha = lerp(ALPHA_REST, ALPHA_PEAK, breath.value)
        }
        .blur(BLUR, BlurredEdgeTreatment.Unbounded)
    val identity = entry.identity
    val still = identity?.let { LocalMascotHost.current.stills?.get(it) }
    if (identity != null && still != null) {
        Image(
            bitmap = still,
            contentDescription = null,
            // The tile draws the still overscaled, so the glow traces the body at the size it is seen.
            modifier = modifier.requiredSize(size * MASCOT_TILE_OVERSCALE).then(glow),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Color(identity.argb), BlendMode.SrcIn),
        )
    } else {
        // The orb's own gradient; a mascot whose still has not landed yet glows in its colour.
        val shape = RoundedCornerShape(cornerRadius)
        val fill = if (identity == null) {
            Modifier.background(agentOrbBrush(entry.orbStyle), shape)
        } else {
            Modifier.background(Color(identity.argb), shape)
        }
        Box(modifier.requiredSize(size).then(glow).then(fill))
    }
}

/** 0 at rest, 1 at the breath's peak; held at the midpoint under reduced motion. */
@Composable
private fun auraBreath(): State<Float> {
    if (LocalReducedMotion.current) return remember { mutableFloatStateOf(HELD) }
    return rememberInfiniteTransition(label = "thinkingAura").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "thinkingAuraBreath",
    )
}

private const val BREATH_MS = 900
private const val SCALE_REST = 1.04f
private const val SCALE_PEAK = 1.18f
private const val ALPHA_REST = 0.45f
private const val ALPHA_PEAK = 0.95f
private const val HELD = 0.6f
private val BLUR = 6.dp
