package com.letta.mobile.ui.components.audio

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * letta-mobile-bcdv: shader palette is hardcoded inside GLSL (yellow /
 * green / red / blue), matching the Edge Gallery original. The original
 * palette was tuned for a warm-to-cool perlin gradient with consistent
 * luminance (~0.6–0.9) so the wave stays visible against a dark scrim;
 * MaterialTheme.colorScheme.primary/secondary/tertiary don't share that
 * property and produced washed-out / banded output.
 */
private const val SHADER =
    """
// The size of the render area.
uniform float2 iResolution;
// The color of the background to render the wave on.
uniform vec4 bgColor;
// Current timestamp in seconds.
uniform float iTime;
// The amplitude of the sound to be visualized.
// From 0 to 1.
uniform float amplitude;
// The extra offset for 1d perlin noise.
uniform float pOffset;

// Curated 4-color palette from the Edge Gallery original.
const vec3 c1 = vec3(0.992, 0.875, 0.522); // yellow
const vec3 c2 = vec3(0.627, 0.816, 0.686); // green
const vec3 c3 = vec3(0.886, 0.372, 0.341); // red
const vec3 c4 = vec3(0.522, 0.694, 0.973); // blue

// Creates a gradient that blends four different colors based on a uv coordinate and animated
// over time.
vec3 mix4(vec2 uv){
  float sinTime1 = sin(iTime / 1.6);
  float sinTime2 = sin(iTime / 1.8);
  return mix(
    mix(c1, c2, smoothstep(0.0 + sinTime1 * 0.1, 0.24 + sinTime1 * 0.1, uv.y)),
    mix(c3, c4, smoothstep(-0.16 - sinTime2 * 0.1, 0.24 - sinTime2 * 0.1, uv.y)),
    smoothstep(0.0, 0.7 + sinTime1 * 0.1, uv.x));
}

float hash(float i) {
	float h = i * 127.1;
	float p = -1. + 2. * fract(sin(h) * 43758.1453123);
  return p;
}

float perlin_noise_1d(float d) {
  float i = floor(d);
  float f = d - i;

  float y = f*f*f* (6. * f*f - 15. * f + 10.);

  float slope1 = hash(i);
  float slope2 = hash(i + 1.0);
  float v1 = f;
  float v2 = f - 1.0;

  float r = mix(slope1 * v1, slope2 * v2, y);
  r = r * 0.5 + 0.5;
  return r;
}

half4 main(float2 fragCoord) {
  float2 uv = fragCoord/iResolution.xy;
  uv.y = 1.0 - uv.y;

  // Add a wavy distortion to the y-coordinate of the uv.
  //
  // Control the amplitude of the wave
  float wave_strength = 0.036;
  // Control the speed of the wave
  float wave_speed = 1.2;
  // Control the frequency of the wave
  float wave_frequency = 4.0;

  // Idle.
  if (amplitude == 0.) {
    uv.y += sin(uv.x * wave_frequency + -iTime * wave_speed) * wave_strength;
  }
  // Visualizing amplitude by sampling the 1d perlin noise at the given offset.
  else {
    uv.y -= perlin_noise_1d(pOffset + uv.x * 3.) * amplitude / 2.0;
  }

  vec3 col = mix4(uv);

  // Define the fade parameters.
  // letta-mobile-vcky.c: fade band lengthened 8x (was 0.10, now 0.80) so
  // the wave color dissolves gradually across the upper portion of the
  // overlay instead of cutting off in a tight 10% band. fade_start kept
  // at 0.24 so the wave still has a solid bottom region.
  float fade_start = 0.24;
  float fade_end = 1.04;

  // Calculate the blend factor using smoothstep for a smooth transition
  float fade_factor = smoothstep(fade_start, fade_end, uv.y);

  // Blend the base color with background color using the fade factor
  vec4 final_color = mix(vec4(col, 1.0), bgColor, fade_factor);

  return vec4(half3(final_color.xyz) * (1 + amplitude * 0.2), final_color.a);
}
"""

private const val AMPLITUDE_FULL_SCALE = 65535.0

/**
 * Shader-based audio animation. Ported from the Google AI Edge Gallery.
 *
 * @param bgColor   Background the wave fades into. The shader's
 *                  `fade_start`/`fade_end` (0.24–0.34) were tuned for a
 *                  near-black scrim; [VoiceRecognizerOverlay] supplies
 *                  that. Callers that pass a light surface color will
 *                  see a washed-out gradient.
 * @param amplitude Raw 0..65535 amplitude from
 *                  [VoiceInputViewModel.convertRmsDbToAmplitude].
 *                  Internally normalized via `pow(x, 0.5)` to emphasize
 *                  the quieter end of the dynamic range.
 *
 * letta-mobile-57t5: divides by 65535, matching VoiceInputViewModel's
 * scale. Previous 32767 divisor over-drove the perlin noise beyond the
 * shader's designed range.
 */
@Composable
fun AudioAnimation(
    bgColor: Color,
    amplitude: Int,
    modifier: Modifier = Modifier,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(SHADER) }
        val shaderBrush = remember { ShaderBrush(shader) }
        var iTime by remember { mutableFloatStateOf(0f) }
        var curPOffset by remember { mutableFloatStateOf(0f) }
        var prevNormalizedAmplitude by remember { mutableDoubleStateOf(0.0) }

        // Use pow(x, 0.5) to make low amplitude levels more significant.
        val normalizedAmplitude = (amplitude / AMPLITUDE_FULL_SCALE).pow(0.5)
        var animatedAmplitude by remember { mutableFloatStateOf(normalizedAmplitude.toFloat()) }

        // Animate the amplitude value whenever amplitude changes.
        LaunchedEffect(amplitude) {
            val animatable = Animatable(initialValue = animatedAmplitude)
            animatable.animateTo(
                targetValue = normalizedAmplitude.toFloat(),
                animationSpec = tween(durationMillis = 100),
            ) {
                animatedAmplitude = this.value
            }
        }

        // Updates the iTime uniform for the shader.
        LaunchedEffect(Unit) {
            while (true) {
                withFrameMillis { frameTimeMs -> iTime = frameTimeMs / 1000f }
            }
        }

        // Shader rendering.
        Canvas(modifier = modifier.fillMaxSize()) {
            if (normalizedAmplitude < 0.2 && prevNormalizedAmplitude >= 0.2) {
                curPOffset = Random.nextFloat() * 1000f
            }
            prevNormalizedAmplitude = normalizedAmplitude

            shader.setFloatUniform("iTime", iTime)
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform("bgColor", bgColor.red, bgColor.green, bgColor.blue, bgColor.alpha)
            shader.setFloatUniform("amplitude", animatedAmplitude)
            shader.setFloatUniform("pOffset", curPOffset)

            drawRect(brush = shaderBrush)
        }
    } else {
        AudioAnimationFallback(
            bgColor = bgColor,
            amplitude = amplitude,
            modifier = modifier,
        )
    }
}

/**
 * letta-mobile-5jx3: pre-API 33 fallback. Compose-only, no RuntimeShader.
 *
 * Draws a vertical-gradient strip with a sine wave whose amplitude is
 * driven by [amplitude] and whose phase advances over time. Won't
 * match the AGSL output but gives the user something rhythmic to look
 * at instead of a dead rectangle while the platform-shader path is
 * unavailable.
 */
@Composable
private fun AudioAnimationFallback(
    bgColor: Color,
    amplitude: Int,
    modifier: Modifier = Modifier,
) {
    val normalized = (amplitude / AMPLITUDE_FULL_SCALE).pow(0.5).toFloat()
    val transition = rememberInfiniteTransition(label = "audioFallback")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "audioFallbackPhase",
    )

    val gradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFFFDDF85), // yellow
            Color(0xFFA0D0AF), // green
            Color(0xFFE25F57), // red
            Color(0xFF85B1F8), // blue
        ),
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = bgColor)
        val width = size.width
        val height = size.height
        val centerY = height * 0.55f
        val waveAmplitude = (height * 0.08f) * (0.4f + normalized.coerceIn(0f, 1f) * 0.8f)
        val pointCount = 64
        val step = width / pointCount
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, centerY)
            for (i in 0..pointCount) {
                val x = i * step
                val theta = (x / width) * 4 * PI.toFloat() + phase
                val y = centerY + sin(theta) * waveAmplitude
                lineTo(x, y)
            }
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }
        drawPath(path = path, brush = gradient, alpha = 0.85f)
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, centerY)
                for (i in 0..pointCount) {
                    val x = i * step
                    val theta = (x / width) * 4 * PI.toFloat() + phase
                    val y = centerY + sin(theta) * waveAmplitude
                    lineTo(x, y)
                }
            },
            color = Color.White.copy(alpha = 0.6f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
        )
        // Silence dummy use of Offset import for older lint stages.
        @Suppress("UNUSED_EXPRESSION") Offset.Zero
    }
}
