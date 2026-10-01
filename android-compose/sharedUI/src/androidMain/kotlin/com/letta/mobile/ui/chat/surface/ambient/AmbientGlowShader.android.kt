package com.letta.mobile.ui.chat.surface.ambient

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_MAIN_UNPREMULTIPLIED
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_SHADER_SOURCE
import com.letta.mobile.ui.ambient.AmbientMotion
import com.letta.mobile.util.Telemetry

/**
 * Android: the shared ambient source compiled as AGSL, exactly as designsystem's
 * AmbientShaderAgentBackground compiles it (AGSL expects an unpremultiplied result). Runtime
 * shaders arrived in Android 13; below that this is null and the shared gradient fallback draws.
 */
@Composable
internal actual fun rememberAmbientGlowShader(): AmbientGlowShader? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { AgslAmbientGlowShader.compile() }
    } else {
        null
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslAmbientGlowShader(private val shader: RuntimeShader) : AmbientGlowShader {
    private val brush = ShaderBrush(shader)

    override fun DrawScope.drawGlow(uniforms: AmbientGlowUniforms) {
        val tint = uniforms.tint
        shader.setFloatUniform("uSize", size.width, uniforms.fieldHeight)
        shader.setFloatUniform("uTime", uniforms.phase)
        shader.setFloatUniform("uAgitation", uniforms.agitation)
        shader.setFloatUniform("uEnvelope", uniforms.envelope)
        shader.setFloatUniform("uStreamEnergy", uniforms.streamEnergy)
        shader.setFloatUniform("uPalettePull", AmbientMotion.PALETTE_HUE_PULL)
        shader.setFloatUniform("uBandTop", uniforms.band.top)
        shader.setFloatUniform("uBandPeak", uniforms.band.peak)
        shader.setFloatUniform("uColor", tint.red, tint.green, tint.blue, tint.alpha * uniforms.gain)
        drawRect(brush = brush)
    }

    companion object {
        /** Null when the AGSL does not compile on this device: loud, so it cannot pass as a flatter glow. */
        fun compile(): AgslAmbientGlowShader? =
            runCatching { AgslAmbientGlowShader(RuntimeShader(AMBIENT_GLOW_SHADER_SOURCE + AMBIENT_GLOW_MAIN_UNPREMULTIPLIED)) }
                .onFailure { failure ->
                    Telemetry.event(
                        AMBIENT_GLOW_TELEMETRY_TAG,
                        "shader.compileFailed",
                        "error" to (failure.message ?: failure::class.simpleName.orEmpty()),
                        level = Telemetry.Level.WARN,
                    )
                }.getOrNull()
    }
}
