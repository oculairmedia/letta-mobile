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

// vcky.b4: organic-noise variant. The brightness baseline still
// anchors at uv.y=0.92 (just inside the strip bottom — composer
// hides it) but is now perturbed by a slow horizontal sine AND a
// low-amplitude 1D perlin layer for texture. Audio-shader-style
// perlin, scaled WAY down so it grains the glow without becoming a
// visualizer.
float hash(float i) {
  float h = i * 127.1;
  return -1.0 + 2.0 * fract(sin(h) * 43758.1453123);
}
float perlin_noise_1d(float d) {
  float i = floor(d);
  float f = d - i;
  float y = f * f * f * (6.0 * f * f - 15.0 * f + 10.0);
  float slope1 = hash(i);
  float slope2 = hash(i + 1.0);
  float v1 = f;
  float v2 = f - 1.0;
  float r = mix(slope1 * v1, slope2 * v2, y);
  return r * 0.5 + 0.5;
}

half4 main(float2 fragCoord) {
  float2 uv = fragCoord / iResolution.xy;

  // Slow horizontal sine — the macro motion the user called the
  // "slightly moving" effect. Kept gentle.
  float wave_speed = 0.9;
  float wave_frequency = 3.2;
  float wave = sin(uv.x * wave_frequency + iTime * wave_speed) * 0.06;

  // vcky.b4: perlin texture layer. Sampled along uv.x (spatial) and
  // shifted by iTime (temporal). Amplitude 0.025 ≈ ~5.4dp on a 216dp
  // strip — visible as grain on the glow's upper tail but small
  // enough to never dominate the macro sine motion.
  float noise = perlin_noise_1d(uv.x * 2.8 + iTime * 0.45);
  float noise_offset = (noise - 0.5) * 0.025;

  float baseline = 0.92 + wave + noise_offset;
  float dist = clamp(baseline - uv.y, 0.0, 1.0);
  float glow = pow(1.0 - clamp(dist / 0.90, 0.0, 1.0), 1.6);

  // Top alpha fade over the outermost 30% so the glow dissolves into
  // bgColor with zero hard line at the upper edge.
  float top_fade = smoothstep(0.0, 0.30, uv.y);

  // vcky.b5: at 50% alpha against a light surface, srcOver
  // (result = src·a + dst·(1-a)) produced too much white from the
  // surface contribution, washing the tint. Drop to 10% — keeps the
  // texture/motion visible but the tint reads through cleanly.
  float a = top_fade * glow * 0.10 * tint.a;

  return vec4(tint.rgb, a);
}
"""

@Composable
fun ThinkingShader(
    tint: Color,
    bgColor: Color,
    modifier: Modifier = Modifier,
    heightDp: Int = 216,
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
    heightDp: Int = 216,
) {
    val transition = rememberInfiniteTransition(label = "thinkingShaderFallback")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    // vcky.b2 fallback: glow anchored at the bottom of the strip (matches
    // the AGSL path's intent — the composer covers this and the visible
    // tail fades upward into the chat list). Horizontal phase shift gives
    // the "slightly moving" sensation without a runtime shader.
    val center = 0.5f + 0.30f * sin(phase)
    // vcky.b5: matches the AGSL path's 10% opacity drop.
    val mixed = tint.copy(alpha = 0.10f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    ) {
        // Horizontal gradient: brightest in the wave center, fades to
        // bgColor (alpha=0) at left/right edges.
        val horizontal = Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to bgColor.copy(alpha = 0f),
                (center - 0.30f).coerceIn(0.02f, 0.98f) to bgColor.copy(alpha = 0f),
                center.coerceIn(0.02f, 0.98f) to mixed,
                (center + 0.30f).coerceIn(0.02f, 0.98f) to bgColor.copy(alpha = 0f),
                1f to bgColor.copy(alpha = 0f),
            ),
        )
        drawRect(brush = horizontal)

        // Vertical mask: bright bottom, fade to transparent at top so
        // the glow "emanates" upward. The very bottom is intentionally
        // NOT faded — the composer overlays it.
        val verticalMask = Brush.verticalGradient(
            0.00f to bgColor.copy(alpha = 1f),
            0.30f to bgColor.copy(alpha = 0f),
            1.00f to bgColor.copy(alpha = 0f),
        )
        drawRect(brush = verticalMask)
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
