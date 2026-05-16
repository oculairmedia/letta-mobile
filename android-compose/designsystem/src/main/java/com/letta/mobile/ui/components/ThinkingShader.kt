package com.letta.mobile.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * letta-mobile-vcky.b: replaces the bordered "Thinking…" chip that previously
 * occupied the bottom slot of the chat list (above the composer) while the
 * agent was streaming. Renders a thin band of an AGSL wave shader whose
 * vertical offset gently oscillates — the "slightly moving bottom" the user
 * asked for.
 *
 * Blending is non-negotiable: the band has zero hard edges. Top and bottom
 * alpha both smoothstep to zero, and the wave color fades into [bgColor]
 * along the same gradients, so the strip dissolves into the chat list's
 * surface color regardless of theme.
 *
 * Fallback (API < 33): a Compose-only animated horizontal gradient with
 * the same top/bottom fade contract. Visually quieter than the AGSL path
 * but the blend invariant holds.
 *
 * @param tint   Accent color for the wave. Caller usually passes the agent
 *               role color so it visually belongs to the response in flight.
 * @param bgColor Background the band must dissolve into — the chat list's
 *                effective surface color. The shader's edge alpha is keyed
 *                to this color rather than to a hardcoded scrim.
 */
private const val SHADER =
    """
uniform float2 iResolution;
uniform float iTime;
uniform vec4 tint;
uniform vec4 bgColor;

// Slow, low-amplitude sine displacement on a vertical strip.
// fragCoord.y travels [0, height]; we map to vertical uv in [0,1].
half4 main(float2 fragCoord) {
  float2 uv = fragCoord / iResolution.xy;

  // Horizontal sine wave centered on the strip's mid-line.
  // wave_strength=0.18 of the strip height — visible but subtle.
  float wave_speed = 0.9;
  float wave_frequency = 3.2;
  float wave = sin(uv.x * wave_frequency + iTime * wave_speed) * 0.18;

  // Distance from the wave's current y-position (0.5 baseline + wave).
  float baseline = 0.5 + wave;
  float dist = abs(uv.y - baseline);

  // Glow falls off over ~0.35 of the strip height.
  float glow = 1.0 - smoothstep(0.0, 0.35, dist);

  // Edge fade: both top (uv.y near 0) and bottom (uv.y near 1) dissolve
  // into bgColor over the outermost 22% of the strip. No hard borders.
  float top_fade    = smoothstep(0.0, 0.22, uv.y);
  float bottom_fade = 1.0 - smoothstep(0.78, 1.0, uv.y);
  float edge_alpha  = top_fade * bottom_fade;

  // Wave color blended with bgColor by glow, then alpha-faded at edges.
  vec3 col = mix(bgColor.rgb, tint.rgb, glow * tint.a);
  float a   = edge_alpha * (0.18 + glow * 0.55);

  return vec4(col, a);
}
"""

@Composable
fun ThinkingShader(
    tint: Color,
    bgColor: Color,
    modifier: Modifier = Modifier,
    heightDp: Int = 24,
) {
    // letta-mobile-vcky.b: API ≥ 33 gets the AGSL path; older devices fall
    // back to the Compose-native gradient so we don't ship a dead rectangle.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(SHADER) }
        val shaderBrush = remember { ShaderBrush(shader) }
        var iTime by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(Unit) {
            while (true) {
                withFrameMillis { frameTimeMs -> iTime = frameTimeMs / 1000f }
            }
        }

        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(heightDp.dp),
        ) {
            shader.setFloatUniform("iTime", iTime)
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform(
                "tint",
                tint.red,
                tint.green,
                tint.blue,
                tint.alpha,
            )
            shader.setFloatUniform(
                "bgColor",
                bgColor.red,
                bgColor.green,
                bgColor.blue,
                bgColor.alpha,
            )
            drawRect(brush = shaderBrush)
        }
    } else {
        ThinkingShaderFallback(
            tint = tint,
            bgColor = bgColor,
            modifier = modifier,
            heightDp = heightDp,
        )
    }
}

@Composable
private fun ThinkingShaderFallback(
    tint: Color,
    bgColor: Color,
    modifier: Modifier = Modifier,
    heightDp: Int = 24,
) {
    val transition = rememberInfiniteTransition(label = "thinkingShaderFallback")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // Cheap "moving" effect on the fallback: a soft horizontal gradient
    // whose tint stop shifts left↔right with the phase. Same fade-to-bg
    // discipline at top and bottom enforced via vertical-gradient compose
    // of brushes (handled by the wave-tinted color alpha already being
    // mixed against bgColor at the edges).
    val center = 0.5f + 0.32f * sin(phase)
    val mixed = tint.copy(alpha = 0.65f).compositeOver(bgColor)
    val brush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to bgColor.copy(alpha = 0f),
            (center - 0.18f).coerceIn(0.05f, 0.95f) to bgColor.copy(alpha = 0f),
            center.coerceIn(0.05f, 0.95f) to mixed,
            (center + 0.18f).coerceIn(0.05f, 0.95f) to bgColor.copy(alpha = 0f),
            1f to bgColor.copy(alpha = 0f),
        ),
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    ) {
        // Top/bottom fade is enforced by sampling a vertical alpha mask:
        // we draw the horizontal-gradient brush at full alpha, then
        // composite a vertical alpha-ramp on top via DstIn. Use a soft
        // 22% inset to mirror the AGSL path.
        drawRect(brush = brush)
        val verticalFade = Brush.verticalGradient(
            0.0f to bgColor.copy(alpha = 1f),
            0.22f to bgColor.copy(alpha = 0f),
            0.78f to bgColor.copy(alpha = 0f),
            1.0f to bgColor.copy(alpha = 1f),
        )
        drawRect(brush = verticalFade)
    }
}

@Suppress("unused")
@Composable
internal fun DefaultThinkingShader(modifier: Modifier = Modifier) {
    ThinkingShader(
        tint = MaterialTheme.colorScheme.primary,
        bgColor = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    )
}
