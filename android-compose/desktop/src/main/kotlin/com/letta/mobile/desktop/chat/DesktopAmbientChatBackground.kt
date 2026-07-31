package com.letta.mobile.desktop.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_SHADER_SOURCE
import com.letta.mobile.ui.ambient.AmbientMotion
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.PI
import kotlin.math.sin

/** Coarse agent activity used to tint the ambient glow. */
internal enum class DesktopAmbientStatus { Idle, Running, Failed, Completed }

private fun DesktopAmbientStatus.toMotionStatus(): AmbientMotionStatus = when (this) {
    DesktopAmbientStatus.Idle -> AmbientMotionStatus.Idle
    DesktopAmbientStatus.Running -> AmbientMotionStatus.Running
    DesktopAmbientStatus.Failed -> AmbientMotionStatus.Failed
    DesktopAmbientStatus.Completed -> AmbientMotionStatus.Completed
}

/**
 * Desktop port of the mobile chat's ambient agent-status glow — the REAL noise
 * shader, not a flat gradient. Desktop Compose renders through Skia, and
 * [RuntimeEffect] compiles the exact SkSL source the Android AGSL path uses
 * ([AMBIENT_GLOW_SHADER_SOURCE]): two noise octaves, domain warp, the works.
 * The layered radial gradient survives only as a fallback if the effect fails
 * to compile on some exotic GPU/driver.
 *
 * Status drives MOTION as well as tint, from the shared [AmbientMotion] table
 * (same floats as the Android renderer, so the platforms stay in step):
 * Running breathes fast and lively, Failed pulses tight and settles high,
 * Completed blooms then decays to a faint afterglow instead of glowing forever.
 */
@Composable
internal fun DesktopAmbientChatBackground(
    status: DesktopAmbientStatus,
    modifier: Modifier = Modifier,
    /**
     * Optional per-agent identity color: non-terminal states lean toward it so
     * the glow says who you're talking to. Failed keeps the semantic error
     * tint untouched — the alarm channel must survive every agent palette.
     */
    identitySeed: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val spec = remember(status) { AmbientMotion.spec(status.toMotionStatus()) }
    val targetColor = remember(status, colorScheme, identitySeed) {
        when (status) {
            DesktopAmbientStatus.Idle -> Color.Transparent
            DesktopAmbientStatus.Running ->
                identitySeed?.let { lerp(colorScheme.tertiary, it, IdentityBlend) } ?: colorScheme.tertiary
            DesktopAmbientStatus.Failed -> colorScheme.error
            DesktopAmbientStatus.Completed -> colorScheme.secondary
        }
    }
    val tint by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 600, easing = EaseInOutCubic),
        label = "ambientTint",
    )
    val speed by animateFloatAsState(
        targetValue = spec.speed,
        animationSpec = tween(durationMillis = 600),
        label = "ambientSpeed",
    )
    val agitation by animateFloatAsState(
        targetValue = spec.agitation,
        animationSpec = tween(durationMillis = 600),
        label = "ambientAgitation",
    )

    // Bloom→settle intensity envelope; continuous states just ease to steady.
    val envelope = remember { Animatable(spec.settledEnvelope) }
    LaunchedEffect(status) {
        val target = AmbientMotion.spec(status.toMotionStatus())
        if (target.isTransient) {
            envelope.snapTo(target.bloomEnvelope)
            envelope.animateTo(
                target.settledEnvelope,
                tween(durationMillis = target.settleMillis, easing = EaseOutCubic),
            )
        } else {
            envelope.animateTo(target.settledEnvelope, tween(durationMillis = 300))
        }
    }

    // Speed-integrated phase (dt * baseRate * speed): rate changes glide
    // instead of popping at a loop seam. Only ticks while the glow is visible.
    var phase by remember { mutableFloatStateOf(0f) }
    val visible = tint.alpha > HiddenAlpha
    if (visible) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last != 0L) {
                        phase += ((now - last) / 1_000_000_000f) * BaseRadiansPerSecond * speed
                    }
                    last = now
                }
            }
        }
    }

    // Compiled once; null only if SkSL compilation fails, in which case the
    // gradient fallback below takes over. Builder and paint are reused across
    // frames (uniform writes overwrite) — only the per-frame Shader handle
    // allocates. Drawn via the native Skia canvas because this Compose
    // version's Shader type does not accept a raw skia Shader.
    val shaderBuilder = remember {
        runCatching { RuntimeShaderBuilder(RuntimeEffect.makeForShader(AMBIENT_GLOW_SHADER_SOURCE)) }
            .getOrNull()
    }
    val shaderPaint = remember { SkiaPaint() }

    Box(modifier = modifier) {
        if (visible) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val intensity = envelope.value
                if (shaderBuilder != null) {
                    shaderBuilder.uniform("uSize", size.width, size.height)
                    shaderBuilder.uniform("uTime", phase)
                    shaderBuilder.uniform("uAgitation", agitation)
                    shaderBuilder.uniform("uEnvelope", intensity)
                    shaderBuilder.uniform("uColor", tint.red, tint.green, tint.blue, tint.alpha)
                    shaderPaint.shader = shaderBuilder.makeShader(null)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(
                            SkiaRect.makeWH(size.width, size.height),
                            shaderPaint,
                        )
                    }
                } else {
                    // Same floats as the shader path — parity by construction.
                    val breath = 0.5f + 0.5f * sin(phase)
                    val wobble = sin(phase * 2.7f) * 0.03f * agitation
                    val radius = size.maxDimension * (0.52f + 0.12f * breath + wobble)
                    val center = Offset(size.width * 0.5f, size.height * 0.92f)
                    drawRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.00f to tint.copy(alpha = tint.alpha * 0.18f * intensity),
                                0.52f to tint.copy(alpha = tint.alpha * 0.08f * intensity),
                                1.00f to tint.copy(alpha = 0f),
                            ),
                            center = center,
                            radius = radius,
                        ),
                    )
                }
            }
        }
        content()
    }
}

private const val HiddenAlpha = 0.001f
private const val IdentityBlend = 0.35f
private const val BaseRadiansPerSecond =
    (2 * PI).toFloat() * 1000f / AmbientMotion.BASE_PERIOD_MILLIS
