package com.letta.mobile.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_MAIN_UNPREMULTIPLIED
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_SHADER_SOURCE
import com.letta.mobile.ui.ambient.AmbientMotion
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import com.letta.mobile.ui.theme.HctColorHarmonizer
import com.letta.mobile.util.Telemetry
import kotlin.math.PI
import kotlin.math.sin

// Status drives BEHAVIOR here, not just tint (see AmbientMotion): uTime arrives
// pre-integrated with the status speed, uAgitation scales the noise
// displacement, uEnvelope scales intensity (bloom→settle for transient states).
// The shader SOURCE lives in sharedLogic (AMBIENT_GLOW_SHADER_SOURCE) and is
// compiled as AGSL here and as SkSL by the desktop renderer — one string, two
// platforms, pixel-equivalent by construction.

@Immutable
enum class AmbientAgentStatus {
    Idle,
    Running,
    Active,
    Failed,
    Completed;

    companion object {
        fun from(agentStatus: String): AmbientAgentStatus = when (agentStatus.trim().lowercase()) {
            "running", "working", "busy", "streaming" -> Running
            "active", "live" -> Active
            "failed", "failure", "error" -> Failed
            "completed", "complete", "done", "success" -> Completed
            else -> Idle
        }
    }
}

internal fun AmbientAgentStatus.toMotionStatus(): AmbientMotionStatus = when (this) {
    AmbientAgentStatus.Idle -> AmbientMotionStatus.Idle
    AmbientAgentStatus.Running -> AmbientMotionStatus.Running
    AmbientAgentStatus.Active -> AmbientMotionStatus.Active
    AmbientAgentStatus.Failed -> AmbientMotionStatus.Failed
    AmbientAgentStatus.Completed -> AmbientMotionStatus.Completed
}

@Composable
fun AmbientShaderAgentBackground(
    agentStatus: String,
    modifier: Modifier = Modifier,
    /** Monotonic pulse incremented when visible assistant content grows. */
    streamActivityPulse: Long = 0L,
    /**
     * Optional per-agent identity color: when set, NON-TERMINAL states
     * (idle/running/active) harmonize toward it, so the ambient background
     * says who you're talking to, not just what state it's in. Failed and
     * Completed always harmonize toward the theme seed — error semantics must
     * survive every agent palette.
     */
    identitySeed: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val status = remember(agentStatus) { AmbientAgentStatus.from(agentStatus) }
    val spec = remember(status) { AmbientMotion.spec(status.toMotionStatus()) }
    val colorScheme = MaterialTheme.colorScheme
    val targetColor = remember(status, colorScheme, identitySeed) {
        val semanticColor = when (status) {
            AmbientAgentStatus.Idle -> Color.Transparent
            AmbientAgentStatus.Running,
            AmbientAgentStatus.Active -> colorScheme.tertiaryContainer
            AmbientAgentStatus.Failed -> colorScheme.errorContainer
            AmbientAgentStatus.Completed -> colorScheme.secondaryContainer
        }
        val seed = when (status) {
            AmbientAgentStatus.Idle,
            AmbientAgentStatus.Running,
            AmbientAgentStatus.Active -> identitySeed ?: colorScheme.primary
            AmbientAgentStatus.Failed,
            AmbientAgentStatus.Completed -> colorScheme.primary
        }
        if (semanticColor == Color.Transparent) {
            semanticColor
        } else {
            HctColorHarmonizer.harmonize(stateColor = semanticColor, seedColor = seed)
        }
    }
    val tint by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 600, easing = EaseInOutCubic),
        label = "ambientAgentTint",
    )

    // Speed glides between statuses so the breath rate never pops; the phase
    // below integrates it continuously, so there is no wrap discontinuity the
    // way scaling a looping 0..2π value would have.
    val speed by animateFloatAsState(
        targetValue = spec.speed,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 600),
        label = "ambientSpeed",
    )
    val agitation by animateFloatAsState(
        targetValue = if (reducedMotion) 0f else spec.agitation,
        // Zeroed under reduced motion for the same reason as the speed above:
        // "animate to no animation" is still animation.
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 600),
        label = "ambientAgitation",
    )

    // Intensity envelope: transient states snap to their bloom and decay to
    // their settled value; continuous states ease to steady. Reduced motion
    // jumps straight to settled — no bloom.
    val envelope = remember { Animatable(spec.settledEnvelope) }
    LaunchedEffect(status, reducedMotion) {
        val target = AmbientMotion.spec(status.toMotionStatus())
        when {
            reducedMotion -> envelope.snapTo(target.settledEnvelope)
            target.isTransient -> {
                envelope.snapTo(target.bloomEnvelope)
                envelope.animateTo(
                    target.settledEnvelope,
                    tween(durationMillis = target.settleMillis, easing = EaseOutCubic),
                )
            }
            else -> envelope.animateTo(target.settledEnvelope, tween(durationMillis = 300))
        }
    }

    Box(modifier = modifier) {
        if (tint.alpha > HiddenAlpha) {
            AmbientCanvas(
                tint = tint,
                animate = !reducedMotion,
                speed = { speed },
                agitation = agitation,
                envelope = envelope.asState(),
                streamActivityPulse = streamActivityPulse,
                modifier = Modifier.matchParentSize(),
            )
        }
        content()
    }
}

@Composable
private fun AmbientCanvas(
    tint: Color,
    animate: Boolean,
    speed: () -> Float,
    agitation: Float,
    envelope: State<Float>,
    streamActivityPulse: Long,
    modifier: Modifier = Modifier,
) {
    val motion = rememberAmbientMotion(animate, speed, streamActivityPulse)
    val shader = rememberAmbientShader()

    if (shader != null) {
        val shaderBrush = remember(shader) { ShaderBrush(shader) }
        Canvas(modifier = modifier.fillMaxSize()) {
            shader.setFloatUniform("uTime", motion.phase)
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uAgitation", agitation)
            shader.setFloatUniform("uEnvelope", envelope.value)
            shader.setFloatUniform("uStreamEnergy", motion.streamEnergy)
            shader.setFloatUniform("uColor", tint.red, tint.green, tint.blue, tint.alpha)
            drawRect(brush = shaderBrush)
        }
    } else {
        Canvas(modifier = modifier.fillMaxSize()) {
            // Same phase / agitation / envelope floats as the shader path, so
            // the two paths stay in step by construction.
            drawAmbientFallback(
                tint = tint,
                phase = motion.phase,
                agitation = agitation,
                envelope = envelope.value,
                streamEnergy = motion.streamEnergy,
            )
        }
    }
}

@Immutable
private data class AmbientCanvasMotion(val phase: Float, val streamEnergy: Float)

@Composable
private fun rememberAmbientMotion(
    animate: Boolean,
    speed: () -> Float,
    streamActivityPulse: Long,
): AmbientCanvasMotion {
    var phase by remember { mutableFloatStateOf(0f) }
    var energy by remember { mutableFloatStateOf(0f) }
    val pulse by rememberUpdatedState(streamActivityPulse)
    var observed by remember { androidx.compose.runtime.mutableLongStateOf(streamActivityPulse) }
    if (animate) LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) {
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, MaxFrameDeltaSeconds)
                if (pulse != observed) energy = (energy + StreamImpulse).coerceAtMost(1f)
                observed = pulse
                energy *= kotlin.math.exp(-dt / StreamEnergyDecaySeconds)
                phase += dt * BaseRadiansPerSecond * speed()
            }
            last = now
        }
    }
    return AmbientCanvasMotion(phase, energy)
}

@Composable
private fun rememberAmbientShader(): RuntimeShader? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    remember {
        runCatching { RuntimeShader(AMBIENT_GLOW_SHADER_SOURCE + AMBIENT_GLOW_MAIN_UNPREMULTIPLIED) }
            .onFailure { failure ->
                Telemetry.event(
                    AMBIENT_TELEMETRY_TAG,
                    "shader.compileFailed",
                    "error" to (failure.message ?: failure::class.simpleName.orEmpty()),
                    level = Telemetry.Level.WARN,
                )
            }.getOrNull()
    }
} else {
    null
}

private fun DrawScope.drawAmbientFallback(
    tint: Color,
    phase: Float,
    agitation: Float,
    envelope: Float,
    streamEnergy: Float,
) {
    val breath = 0.5f + 0.5f * sin(phase)
    // No noise field here; a second sine at an unrelated frequency scaled by
    // agitation approximates the shader's drift so the fallback still gets
    // livelier when the shader would.
    val wobble = sin(phase * 2.7f) * 0.03f * agitation
    // letta-mobile-shader-position-2026-08-06: anchor pushed from 0.92 to
    // 0.985 so the bright zone sits at the very bottom edge; radius
    // tightened (0.56 -> 0.46) so the band is short and subtle rather
    // than broad — pairs with shader-side alpha narrowing for a thin
    // under-composer glow.
    val radius = size.maxDimension * (0.46f + 0.12f * breath + wobble)
    val center = Offset(size.width * 0.5f, size.height * 0.985f)
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to tint.copy(alpha = tint.alpha * (0.18f + 0.01f * streamEnergy) * envelope),
                0.52f to tint.copy(alpha = tint.alpha * 0.08f * envelope),
                1.00f to tint.copy(alpha = 0f),
            ),
            center = center,
            radius = radius,
        ),
    )
}

private const val AMBIENT_TELEMETRY_TAG = "AmbientShader"
private const val HiddenAlpha = 0.001f
private const val MaxFrameDeltaSeconds = 0.1f
private const val StreamImpulse = 0.35f
private const val StreamEnergyDecaySeconds = 0.9f
private const val BaseRadiansPerSecond =
    (2 * PI).toFloat() * 1000f / AmbientMotion.BASE_PERIOD_MILLIS
