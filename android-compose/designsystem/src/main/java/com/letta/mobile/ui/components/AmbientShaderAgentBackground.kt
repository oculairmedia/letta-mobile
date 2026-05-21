package com.letta.mobile.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.letta.mobile.ui.theme.HctColorHarmonizer
import kotlin.math.PI

private const val AmbientShader =
    """
uniform float2 uSize;
uniform float uTime;
uniform vec4 uColor;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash(i + float2(0.0, 0.0)), hash(i + float2(1.0, 0.0)), u.x),
        mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x),
        u.y
    );
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(uSize, float2(1.0, 1.0));
    float aspect = uSize.x / max(uSize.y, 1.0);
    float2 p = float2((uv.x - 0.50) * aspect, uv.y - 0.82);

    float breath = 0.5 + 0.5 * sin(uTime);
    float drift = noise(float2(uv.x * 2.2 + uTime * 0.08, uv.y * 1.6 - uTime * 0.05));
    float radius = mix(0.42, 0.62, breath) + (drift - 0.5) * 0.08;
    float glow = 1.0 - smoothstep(0.0, radius, length(p));

    float upperFade = 1.0 - smoothstep(0.0, 0.78, uv.y);
    float lowerAnchor = smoothstep(0.25, 1.0, uv.y);
    float alpha = glow * upperFade * lowerAnchor * 0.18 * uColor.a;
    return half4(uColor.rgb, alpha);
}
"""

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

@Composable
fun AmbientShaderAgentBackground(
    agentStatus: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val reducedMotion = rememberReducedMotionEnabled()
    val status = remember(agentStatus) { AmbientAgentStatus.from(agentStatus) }
    val colorScheme = MaterialTheme.colorScheme
    val targetColor = remember(status, colorScheme) {
        val semanticColor = when (status) {
            AmbientAgentStatus.Idle -> Color.Transparent
            AmbientAgentStatus.Running,
            AmbientAgentStatus.Active -> colorScheme.tertiaryContainer
            AmbientAgentStatus.Failed -> colorScheme.errorContainer
            AmbientAgentStatus.Completed -> colorScheme.secondaryContainer
        }
        if (semanticColor == Color.Transparent) {
            semanticColor
        } else {
            HctColorHarmonizer.harmonize(
                stateColor = semanticColor,
                seedColor = colorScheme.primary,
            )
        }
    }
    val tint by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 600, easing = EaseInOutCubic),
        label = "ambientAgentTint",
    )

    Box(modifier = modifier) {
        if (tint.alpha > HiddenAlpha) {
            AmbientCanvas(
                tint = tint,
                animate = !reducedMotion,
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
    modifier: Modifier = Modifier,
) {
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "ambientAgentBackground")
        val animatedPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "ambientAgentPhase",
        )
        animatedPhase
    } else {
        0f
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(AmbientShader) }
        val shaderBrush = remember { ShaderBrush(shader) }
        Canvas(modifier = modifier.fillMaxSize()) {
            shader.setFloatUniform("uTime", phase)
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uColor", tint.red, tint.green, tint.blue, tint.alpha)
            drawRect(brush = shaderBrush)
        }
    } else {
        Canvas(modifier = modifier.fillMaxSize()) {
            drawAmbientFallback(tint = tint, phase = phase)
        }
    }
}

private fun DrawScope.drawAmbientFallback(tint: Color, phase: Float) {
    val breath = 0.5f + 0.5f * kotlin.math.sin(phase)
    val radius = size.maxDimension * (0.52f + 0.12f * breath)
    val center = Offset(size.width * 0.5f, size.height * 0.92f)
    drawRect(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0.00f to tint.copy(alpha = tint.alpha * 0.18f),
                0.52f to tint.copy(alpha = tint.alpha * 0.08f),
                1.00f to tint.copy(alpha = 0f),
            ),
            center = center,
            radius = radius,
        ),
    )
}

private const val HiddenAlpha = 0.001f
