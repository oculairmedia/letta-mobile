package com.letta.mobile.ui.chat.surface.ambient

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr

/**
 * Android: the shared wave source compiled as AGSL with the identity main, as the thinking glow is
 * (AmbientGlowShader.android.kt). Runtime shaders arrived in Android 13; below that this is null and
 * the wave's gradient fallback draws, as the dictation overlay's did.
 */
internal actual fun createVoiceWaveShader(): VoiceWaveShader? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslVoiceWaveShader.compile() else null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslVoiceWaveShader(private val shader: RuntimeShader) : VoiceWaveShader {
    private val brush = ShaderBrush(shader)

    override fun DrawScope.drawWave(frame: VoiceWaveFrame) {
        shader.setFloatUniform("iResolution", size.width, size.height)
        shader.setFloatUniform("iTime", frame.time)
        shader.setFloatUniform("amplitude", frame.amplitude)
        shader.setFloatUniform("pOffset", frame.noiseOffset)
        shader.color("bgColor", frame.background)
        shader.color("c1", frame.c1)
        shader.color("c2", frame.c2)
        shader.color("c3", frame.c3)
        shader.color("c4", frame.c4)
        drawRect(brush = brush)
    }

    companion object {
        /** Null when the AGSL does not compile on this device: loud, so it cannot pass as a flat band. */
        fun compile(): AgslVoiceWaveShader? =
            runCatching { AgslVoiceWaveShader(RuntimeShader(VOICE_WAVE_SHADER_SOURCE + VOICE_WAVE_MAIN_UNPREMULTIPLIED)) }
                .onFailure { failure ->
                    Telemetry.event(
                        VOICE_WAVE_TELEMETRY_TAG,
                        "shader.compileFailed",
                        "error" attr (failure.message ?: failure::class.simpleName.orEmpty()),
                        level = Telemetry.Level.WARN,
                    )
                }.getOrNull()
    }
}

/** A colour uniform as four floats. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RuntimeShader.color(name: String, color: Color) = setFloatUniform(name, color.red, color.green, color.blue, color.alpha)
