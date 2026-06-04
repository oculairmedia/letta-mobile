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

// letta-mobile-9dx2y: ported wholesale from the working TTS shader
// (audio/AudioAnimation.kt). The previous p2auf approach painted
// deepened THEME accents at a global alpha of 0.20 — over the dark
// chat surface that low alpha composited every hue toward grey, so the
// glow read white and the "chaser" was invisible. The fix mirrors the
// TTS technique that DOES read as clear color on the same surface:
//   1. a CURATED VIVID hardcoded palette (theme accents are too
//      pale/grey to assert as hue),
//   2. mix4(uv) — the palette blended across uv with two slow sines so
//      the colors visibly drift/chase across the band over time,
//   3. render the color at FULL opacity and dissolve into the ACTUAL
//      bgColor in COLOR SPACE (never to generic grey, never via a low
//      global alpha).
// The thin-band geometry, bottom-hugging glow body and upward
// edge-blend are kept exactly as before — only the color model changed.

// Curated 4-color palette (same vivid set the TTS shader uses): these
// have consistent mid-high luminance so they read as hue against a dark
// scrim. tint/tint2/tint3 are intentionally unused for color now; they
// only carry tint.a as the overall glow strength.
const vec3 c1 = vec3(0.992, 0.875, 0.522); // yellow
const vec3 c2 = vec3(0.627, 0.816, 0.686); // green
const vec3 c3 = vec3(0.886, 0.372, 0.341); // red
const vec3 c4 = vec3(0.522, 0.694, 0.973); // blue

// Blends the four palette colors across a uv coordinate, animated over
// time with two slow sines — the visible multi-color drift/chase. This
// is the TTS shader's mix4(); the slow sines make the four colors
// migrate across the band so the band visibly cycles through the
// palette instead of cross-fading as one block.
vec3 mix4(vec2 uv) {
  float sinTime1 = sin(iTime / 1.6);
  float sinTime2 = sin(iTime / 1.8);
  return mix(
    mix(c1, c2, smoothstep(0.0 + sinTime1 * 0.1, 0.24 + sinTime1 * 0.1, uv.y)),
    mix(c3, c4, smoothstep(-0.16 - sinTime2 * 0.1, 0.24 - sinTime2 * 0.1, uv.y)),
    smoothstep(0.0, 0.7 + sinTime1 * 0.1, uv.x));
}

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
  // "slightly moving" effect. Kept gentle. (geometry unchanged)
  float wave_speed = 0.9;
  float wave_frequency = 3.2;
  float wave = sin(uv.x * wave_frequency + iTime * wave_speed) * 0.06;

  // Two-octave perlin with a slow 2D crawl so the grain churns
  // organically rather than sliding in one direction. (geometry unchanged)
  float nA = perlin_noise_1d(uv.x * 2.8 + iTime * 0.45 + uv.y * 1.5);
  float nB = perlin_noise_1d(uv.x * 5.3 - iTime * 0.21 + 11.0);
  float noise = nA * 0.7 + nB * 0.3;
  float noise_offset = (noise - 0.5) * 0.025;

  // Glow body hugs the bottom of the strip; the composer covers the
  // brightest part and a soft halo rises above. (geometry unchanged)
  float baseline = 0.90 + wave + noise_offset;
  float dist = clamp(baseline - uv.y, 0.0, 1.0);
  // Band hugging the bottom; dissolves upward. Divisor controls height
  // (too large = the colour washes up over the messages); pow keeps the
  // top edge soft. 0.55 = a touch taller than the slim version but still
  // an accent at the composer, NOT a screen-wide wash.
  float glow = pow(1.0 - clamp(dist / 0.55, 0.0, 1.0), 2.6);

  // Vivid drifting multi-color body, then SOFTENED by pre-mixing the
  // background colour into it. BG_MIX pulls the vivid palette toward the
  // chat surface tone so the glow reads as a muted/soft colour rather
  // than a bright wash — keeps the full band height and the drift, just
  // less saturated. Color-space (toward the real bg), so never grey.
  float BG_MIX = 0.70; // 0=fully vivid, 1=invisible (== bg)
  vec3 col = mix(mix4(uv), bgColor.rgb, BG_MIX);

  // COLOR-SPACE dissolve (TTS technique): render col at FULL opacity and
  // mix toward the ACTUAL bgColor as we approach the top of the strip,
  // so the band dissolves into the chat surface with no hard edge and
  // never washes to generic grey. fade keyed to the same upward intent
  // as before (top ~30% blends out) and additionally damped by the glow
  // body so the diffuse tail above the composer fades cleanly.
  float top_fade = smoothstep(0.0, 0.30, uv.y);
  // Dissolve to bg toward the top edge (softening is handled by BG_MIX
  // above, so the body stays full-height; this just blends the edges).
  float fade = 1.0 - top_fade * glow;
  vec4 final_color = mix(vec4(col, 1.0), bgColor, fade);

  // tint.a is the caller-supplied overall glow strength multiplier.
  // NOTE: do NOT scale this alpha down to make the effect subtler — low
  // alpha over the dark surface composites the color toward grey (the
  // exact bug we fixed). To make it subtler, shrink the glow BAND
  // (dist divisor / pow above) so less of the strip is colored while the
  // color that IS shown stays full-opacity and vivid.
  return vec4(final_color.xyz, final_color.a * tint.a);
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

    // letta-mobile-9dx2y: parity with the AGSL path — drift across the
    // SAME curated vivid palette (yellow/green/red/blue) instead of the
    // pale theme accents, so the pre-API-33 fallback also reads as clear
    // color on the dark surface. A slow cycle migrates the palette so the
    // strip visibly chases through the colors over time.
    val driftPhase = if (animate) {
        val driftTransition = rememberInfiniteTransition(label = "thinkingShaderColorDrift")
        val animatedDrift by driftTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 9000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "drift",
        )
        animatedDrift
    } else {
        0f
    }
    val paletteYellow = Color(0xFFFDDF85)
    val paletteGreen = Color(0xFFA0D0AF)
    val paletteRed = Color(0xFFE25F57)
    val paletteBlue = Color(0xFF85B1F8)
    val w1 = 0.5f + 0.5f * sin(driftPhase)
    val w2 = 0.5f + 0.5f * sin(driftPhase * 0.61803f + 2.094f)
    val drifted = lerp(lerp(paletteYellow, paletteGreen, w1), lerp(paletteRed, paletteBlue, w1), w2)

    // vcky.b2 fallback: glow anchored at the bottom of the strip (matches
    // the AGSL path's intent — the composer covers this and the visible
    // tail fades upward into the chat list). Horizontal phase shift gives
    // the "slightly moving" sensation without a runtime shader.
    val center = 0.5f + 0.30f * sin(phase)
    // Render at high opacity so the vivid color reads on the dark
    // surface (the surrounding gradient still dissolves into bgColor).
    val mixed = drifted.copy(alpha = 0.85f)

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
