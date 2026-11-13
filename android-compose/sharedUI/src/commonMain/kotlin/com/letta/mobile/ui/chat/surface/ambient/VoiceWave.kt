package com.letta.mobile.ui.chat.surface.ambient

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/*
 * The voice band's reactive wave: the audio-reactive shader of Android's dictation overlay
 * (designsystem AudioAnimation, from the Google AI Edge Gallery), moved here so Android and desktop
 * draw the same thing. It is compiled per platform the way the thinking glow is
 * (createAmbientGlowShader): AGSL on Android 13+, Skia SkSL on desktop and web, each appending its own
 * one-line main to the one shared source. Two changes from the overlay's copy: the palette comes from
 * the theme instead of four fixed colours tuned for a black scrim, and the wave dissolves into the
 * band's own surface colour, so it sits inside the chat bar's band rather than over the screen.
 */

/**
 * The shared wave source, without a main. [voiceWaveColor] returns an UNPREMULTIPLIED colour;
 * each platform appends its main ([VOICE_WAVE_MAIN_UNPREMULTIPLIED] for AGSL,
 * [VOICE_WAVE_MAIN_PREMULTIPLIED] for Skia). Uniforms:
 * - iResolution: the band's size in px
 * - bgColor: the surface the wave dissolves into (opaque)
 * - iTime: seconds of animation (the overlay's frame clock)
 * - amplitude: the smoothed level, 0..1; exactly 0 draws the resting sine
 * - pOffset: where the 1-D noise is sampled while the level is up; moved each time it drops
 * - c1..c4: the palette, from the theme
 */
internal const val VOICE_WAVE_SHADER_SOURCE: String =
    """
uniform float2 iResolution;
uniform vec4 bgColor;
uniform float iTime;
uniform float amplitude;
uniform float pOffset;
uniform vec4 c1;
uniform vec4 c2;
uniform vec4 c3;
uniform vec4 c4;

// The four colours blended across the band, drifting on two slow sines.
float3 mix4(float2 uv) {
  float sinTime1 = sin(iTime / 1.6);
  float sinTime2 = sin(iTime / 1.8);
  return mix(
    mix(c1.rgb, c2.rgb, smoothstep(0.0 + sinTime1 * 0.1, 0.24 + sinTime1 * 0.1, uv.y)),
    mix(c3.rgb, c4.rgb, smoothstep(-0.16 - sinTime2 * 0.1, 0.24 - sinTime2 * 0.1, uv.y)),
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
  float r = mix(slope1 * f, slope2 * (f - 1.0), y);
  return r * 0.5 + 0.5;
}

half4 voiceWaveColor(float2 fragCoord) {
  float2 uv = fragCoord / max(iResolution.xy, float2(1.0, 1.0));
  uv.y = 1.0 - uv.y;
  // At rest: a slow sine. With a level: the edge lifted by 1-D noise in proportion to it.
  if (amplitude == 0.0) {
    uv.y += sin(uv.x * 4.0 - iTime * 1.2) * 0.036;
  } else {
    uv.y -= perlin_noise_1d(pOffset + uv.x * 3.0) * amplitude / 2.0;
  }
  float3 col = mix4(uv);
  // Solid along the bottom, dissolving upward into the band's surface.
  float fade = smoothstep(0.24, 1.04, uv.y);
  float4 finalColor = mix(float4(col, 1.0), bgColor, fade);
  return half4(clamp(finalColor.rgb * (1.0 + amplitude * 0.2), 0.0, 1.0), finalColor.a);
}
"""

/** AGSL's main: Android expects an unpremultiplied result. */
internal const val VOICE_WAVE_MAIN_UNPREMULTIPLIED: String =
    "\nhalf4 main(float2 fragCoord) { return voiceWaveColor(fragCoord); }\n"

/** Skia's main: a runtime effect returns a premultiplied result. */
internal const val VOICE_WAVE_MAIN_PREMULTIPLIED: String =
    "\nhalf4 main(float2 fragCoord) { half4 c = voiceWaveColor(fragCoord); return half4(c.rgb * c.a, c.a); }\n"

/** One frame's inputs to the wave, reused across frames (nothing is allocated per frame). */
internal class VoiceWaveFrame {
    var time: Float = 0f
    var amplitude: Float = 0f
    var noiseOffset: Float = 0f
    var background: Color = Color.Transparent
    var c1: Color = Color.Transparent
    var c2: Color = Color.Transparent
    var c3: Color = Color.Transparent
    var c4: Color = Color.Transparent

    /** Takes the wave's colours from [scheme], dissolving into [background]. */
    fun paint(scheme: ColorScheme, background: Color) {
        this.background = background
        c1 = scheme.tertiary
        c2 = scheme.secondary
        c3 = scheme.primary
        c4 = scheme.primaryContainer
    }
}

/** The platform's compiled wave, drawing one frame over the whole draw scope. */
internal interface VoiceWaveShader {
    fun DrawScope.drawWave(frame: VoiceWaveFrame)

    /** Frees what the shader holds natively (desktop's Skia builder and paint); idempotent. */
    fun release() {}
}

/**
 * The wave compiled for this platform (Skia RuntimeEffect on desktop and web, AGSL RuntimeShader on
 * Android 13+), or null where it cannot run; the wave's gradient fallback draws then.
 */
internal expect fun createVoiceWaveShader(): VoiceWaveShader?

/**
 * The page's compiled wave, compiled the first time a wave shows and kept until the page goes, so
 * the band moving between the wave and the thinking glow does not compile it again.
 */
@Stable
internal class VoiceWaveShaders {
    private var compiled = false
    private var shader: VoiceWaveShader? = null

    fun get(): VoiceWaveShader? {
        if (!compiled) {
            compiled = true
            shader = createVoiceWaveShader()
        }
        return shader
    }

    fun release() {
        shader?.release()
        shader = null
        compiled = false
    }
}

/** The page's [VoiceWaveShaders]; null outside a chat page, where each wave keeps its own. */
internal val LocalVoiceWaveShaders = staticCompositionLocalOf<VoiceWaveShaders?> { null }

/** A [VoiceWaveShaders] released with the composition that remembers it. */
@Composable
internal fun rememberVoiceWaveShaders(): VoiceWaveShaders {
    val shaders = remember { VoiceWaveShaders() }
    DisposableEffect(shaders) { onDispose { shaders.release() } }
    return shaders
}

/**
 * The reactive wave over [modifier]'s box, dissolving into [background] (the band's surface), its
 * height following [level] (0..1, read every frame, so a changing level recomposes nothing).
 *
 * It animates on the frame clock only while it is composed, and only in the draw phase. Under
 * reduced motion it is one still frame of the gradient fallback: no clock, no shader, no level.
 */
@Composable
internal fun VoiceWave(level: () -> Float, background: Color, modifier: Modifier = Modifier) {
    val reducedMotion = LocalReducedMotion.current
    val shaders = LocalVoiceWaveShaders.current ?: rememberVoiceWaveShaders()
    val shader = if (reducedMotion) null else remember(shaders) { shaders.get() }
    val motion = remember { VoiceWaveMotion() }
    val frame = remember { VoiceWaveFrame() }
    val fallback = remember { VoiceWaveFallback() }
    frame.paint(MaterialTheme.colorScheme, background)
    val currentLevel by rememberUpdatedState(level)
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            motion.rest()
            return@LaunchedEffect
        }
        var last = 0L
        // An infinite animation: it is the wave's clock, not work a test should wait on.
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / NANOS_PER_SECOND).coerceIn(0f, ChatMotionTokens.AmbientGlow.MAX_FRAME_DELTA_SECONDS)
                    motion.advance(dt, currentLevel())
                }
                last = now
            }
        }
    }
    Spacer(
        modifier
            .testTag(VOICE_WAVE_TAG)
            .semantics { voiceWaveAnimated = !reducedMotion }
            .drawBehind {
                frame.time = motion.time.floatValue
                frame.amplitude = motion.amplitude.floatValue
                frame.noiseOffset = motion.noiseOffset.floatValue
                if (shader != null) with(shader) { drawWave(frame) } else with(fallback) { draw(frame) }
            },
    )
}

/** The wave's clock, its glided level and where its noise is sampled; read at draw time. */
@Stable
private class VoiceWaveMotion {
    val time = mutableFloatStateOf(0f)
    val amplitude = mutableFloatStateOf(0f)
    val noiseOffset = mutableFloatStateOf(0f)

    /** One frame of [dt] seconds toward [level]: the overlay's 100 ms glide, a new noise offset each time it drops. */
    fun advance(dt: Float, level: Float) {
        // Wrapped well before float resolution suffers; the wave jumps once at the wrap.
        time.floatValue = (time.floatValue + dt) % TIME_WRAP_SECONDS
        val target = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        val previous = amplitude.floatValue
        var next = previous + (target - previous) * (1f - exp(-dt / LEVEL_GLIDE_SECONDS))
        if (target == 0f && next < LEVEL_SNAP) next = 0f
        amplitude.floatValue = next
        if (next < NOISE_RESEED_BELOW && previous >= NOISE_RESEED_BELOW) noiseOffset.floatValue = Random.nextFloat() * NOISE_RANGE
    }

    /** A still frame: the resting wave at time zero. */
    fun rest() {
        time.floatValue = 0f
        amplitude.floatValue = 0f
    }
}

/**
 * The overlay's pre-Android-13 fallback in the theme's colours: the band's surface with a sine
 * whose height follows the level, filled with the palette as a vertical gradient. Its path and
 * brush are reused across frames.
 */
private class VoiceWaveFallback {
    private val path = Path()
    private var brushColors: List<Color> = emptyList()
    private var brush: Brush? = null

    fun DrawScope.draw(frame: VoiceWaveFrame) {
        drawRect(frame.background)
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return
        val centreY = height * FALLBACK_CENTRE
        val waveHeight = height * FALLBACK_WAVE * (FALLBACK_REST + frame.amplitude * FALLBACK_GAIN)
        val phase = frame.time * FALLBACK_PHASE_RATE
        path.reset()
        path.moveTo(0f, centreY)
        val step = width / FALLBACK_POINTS
        for (i in 0..FALLBACK_POINTS) {
            val x = i * step
            path.lineTo(x, centreY + sin((x / width) * FALLBACK_TURNS + phase) * waveHeight)
        }
        path.lineTo(width, height)
        path.lineTo(0f, height)
        path.close()
        drawPath(path = path, brush = brushFor(frame), alpha = FALLBACK_ALPHA)
    }

    private fun brushFor(frame: VoiceWaveFrame): Brush {
        val held = brush
        if (held != null && brushColors.size == 4 && brushColors[0] == frame.c1 && brushColors[1] == frame.c2 &&
            brushColors[2] == frame.c3 && brushColors[3] == frame.c4
        ) {
            return held
        }
        val colors = listOf(frame.c1, frame.c2, frame.c3, frame.c4)
        brushColors = colors
        return Brush.verticalGradient(colors).also { brush = it }
    }
}

/** Tags the reactive wave while it is drawn. */
internal const val VOICE_WAVE_TAG = "voice-band-wave"

internal const val VOICE_WAVE_TELEMETRY_TAG = "VoiceBandWave"

/** Whether the wave is moving (false under reduced motion: one still frame). */
internal val VoiceWaveAnimatedKey = SemanticsPropertyKey<Boolean>("VoiceWaveAnimated")
internal var SemanticsPropertyReceiver.voiceWaveAnimated by VoiceWaveAnimatedKey

private const val NANOS_PER_SECOND = 1_000_000_000f
private const val TIME_WRAP_SECONDS = 3_600f
private const val LEVEL_GLIDE_SECONDS = 0.1f
private const val LEVEL_SNAP = 0.005f
private const val NOISE_RESEED_BELOW = 0.2f
private const val NOISE_RANGE = 1_000f
private const val FALLBACK_CENTRE = 0.55f
private const val FALLBACK_WAVE = 0.08f
private const val FALLBACK_REST = 0.4f
private const val FALLBACK_GAIN = 0.8f
private const val FALLBACK_POINTS = 64
private const val FALLBACK_TURNS = (4 * PI).toFloat()
private const val FALLBACK_PHASE_RATE = (2 * PI / 1.8).toFloat()
private const val FALLBACK_ALPHA = 0.85f
