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
import androidx.compose.ui.graphics.lerp
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
uniform vec4 tint2;
uniform vec4 tint3;
uniform vec4 bgColor;

// p2auf: themed, slowly-evolving variant. Three theme accents
// (tint/tint2/tint3) are blended along a very slow time phase so the
// glow drifts across the app palette (~60-90s cycle), and the perlin
// texture is now a two-octave field with a slow 2D crawl so the grain
// visibly evolves instead of merely scrolling. All draw-phase; no
// composition cost.
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

// p2auf: slow palette drift across the three themed accents. Two
// out-of-phase slow sines pick a position in a tint -> tint2 -> tint3
// loop so no single accent dominates and the transition never snaps.
vec3 driftColor(float t) {
  float p = t * 0.08; // ~78s for a full 2*pi cycle
  float w1 = 0.5 + 0.5 * sin(p);
  float w2 = 0.5 + 0.5 * sin(p * 0.61803 + 2.094);
  vec3 ab = mix(tint.rgb, tint2.rgb, w1);
  return mix(ab, tint3.rgb, w2 * 0.5);
}

half4 main(float2 fragCoord) {
  float2 uv = fragCoord / iResolution.xy;

  // Slow horizontal sine — the macro motion the user called the
  // "slightly moving" effect. Kept gentle.
  float wave_speed = 0.9;
  float wave_frequency = 3.2;
  float wave = sin(uv.x * wave_frequency + iTime * wave_speed) * 0.06;

  // p2auf: two-octave perlin with a slow 2D crawl. Octave A is the
  // original coarse grain (now also drifting on uv.y over time);
  // octave B is finer and slower, summed at reduced amplitude so the
  // field churns organically rather than sliding in one direction.
  float nA = perlin_noise_1d(uv.x * 2.8 + iTime * 0.45 + uv.y * 1.5);
  float nB = perlin_noise_1d(uv.x * 5.3 - iTime * 0.21 + 11.0);
  float noise = nA * 0.7 + nB * 0.3;
  float noise_offset = (noise - 0.5) * 0.025;

  float baseline = 0.92 + wave + noise_offset;
  float dist = clamp(baseline - uv.y, 0.0, 1.0);
  float glow = pow(1.0 - clamp(dist / 0.90, 0.0, 1.0), 1.6);

  // Top alpha fade over the outermost 30% so the glow dissolves into
  // bgColor with zero hard line at the upper edge.
  float top_fade = smoothstep(0.0, 0.30, uv.y);

  // p2auf: the previous 0.10 alpha read as a faint white wash on
  // light surfaces — the color never asserted itself. Raise the glow
  // alpha substantially (0.45) so the themed accent is actually
  // visible, and boost saturation by pushing the drifted color away
  // from its own luminance (a cheap saturate) so it reads as COLOR,
  // not a grey/white veil. Edge fades still guarantee zero hard lines.
  float a = top_fade * glow * 0.45 * tint.a;

  // p2auf: the visible color is the slowly-drifting themed blend.
  vec3 col = driftColor(iTime);
  float lum = dot(col, vec3(0.299, 0.587, 0.114));
  col = clamp(mix(vec3(lum), col, 1.6), 0.0, 1.0); // saturate ~1.6x
  return vec4(col, a);
}
"""

@Composable
fun ThinkingShader(
    tint: Color,
    bgColor: Color,
    modifier: Modifier = Modifier,
    heightDp: Int = 216,
    animate: Boolean = true,
    // p2auf: secondary/tertiary themed accents the glow slowly drifts
    // toward. Default to [tint] so existing single-color callers are
    // unchanged; pass distinct theme colors for the multi-color drift.
    tint2: Color = tint,
    tint3: Color = tint,
) {
    // letta-mobile-vcky.b: API ≥ 33 gets the AGSL path; older devices fall
    // back to the Compose-native gradient so we don't ship a dead rectangle.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(SHADER) }
        val shaderBrush = remember { ShaderBrush(shader) }
        var iTime by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(animate) {
            if (animate) {
                while (true) {
                    withFrameMillis { frameTimeMs -> iTime = frameTimeMs / 1000f }
                }
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
                "tint2",
                tint2.red,
                tint2.green,
                tint2.blue,
                tint2.alpha,
            )
            shader.setFloatUniform(
                "tint3",
                tint3.red,
                tint3.green,
                tint3.blue,
                tint3.alpha,
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
            animate = animate,
            tint2 = tint2,
            tint3 = tint3,
        )
    }
}

@Composable
private fun ThinkingShaderFallback(
    tint: Color,
    bgColor: Color,
    modifier: Modifier = Modifier,
    heightDp: Int = 216,
    animate: Boolean = true,
    tint2: Color = tint,
    tint3: Color = tint,
) {
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "thinkingShaderFallback")
        val animatedPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
        animatedPhase
    } else {
        0f
    }

    // p2auf: slow themed color drift, parity with the AGSL driftColor.
    // A separate, much slower cycle so old devices still see the palette
    // evolve rather than sitting on one accent.
    val driftPhase = if (animate) {
        val driftTransition = rememberInfiniteTransition(label = "thinkingShaderColorDrift")
        val animatedDrift by driftTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 78000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "drift",
        )
        animatedDrift
    } else {
        0f
    }
    val w1 = 0.5f + 0.5f * sin(driftPhase)
    val w2 = 0.5f + 0.5f * sin(driftPhase * 0.61803f + 2.094f)
    val drifted = lerp(lerp(tint, tint2, w1), tint3, w2 * 0.5f)

    // vcky.b2 fallback: glow anchored at the bottom of the strip (matches
    // the AGSL path's intent — the composer covers this and the visible
    // tail fades upward into the chat list). Horizontal phase shift gives
    // the "slightly moving" sensation without a runtime shader.
    val center = 0.5f + 0.30f * sin(phase)
    // p2auf: match the AGSL path's stronger 45% glow so the themed
    // color actually reads instead of washing to white.
    val mixed = drifted.copy(alpha = 0.45f)

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
