package com.letta.mobile.ui.components.beam

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr

/**
 * Android: the shared beam source compiled as AGSL. Runtime shaders arrived in Android 13; below that
 * this is null and the Canvas fallback draws.
 */
internal actual fun createBorderBeamShader(): BorderBeamRenderer? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AgslBorderBeam.compile() else null

/** One beam's AGSL shader; uniforms are per instance, so every beam owns one. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AgslBorderBeam(private val shader: RuntimeShader) : BorderBeamRenderer {
    private val brush = ShaderBrush(shader)

    override fun DrawScope.drawBeam(frame: BeamFrame) {
        val uniforms = BeamUniforms(this, frame)
        shader.setFloatUniform("size", uniforms.width, uniforms.height)
        shader.setFloatUniform("density", uniforms.density)
        shader.setFloatUniform("cornerRadius", uniforms.cornerRadius)
        shader.setFloatUniform("phase", uniforms.phase)
        shader.setFloatUniform("direction", uniforms.direction)
        shader.setFloatUniform("mode", uniforms.mode)
        shader.setFloatUniform("arc", uniforms.arc)
        shader.setFloatUniform("glow", uniforms.glow)
        shader.setFloatUniform("intensity", uniforms.intensity)
        shader.setFloatUniform("count", uniforms.count)
        shader.setFloatUniform("colors", uniforms.colors)
        drawRect(brush = brush)
    }

    companion object {
        /** Null when the AGSL does not compile on this device: logged, so it cannot pass as the fallback. */
        fun compile(): AgslBorderBeam? =
            runCatching { AgslBorderBeam(RuntimeShader(BORDER_BEAM_SHADER)) }
                .onFailure { failure ->
                    Telemetry.event(
                        BORDER_BEAM_TELEMETRY_TAG,
                        "shader.compileFailed",
                        "error" attr (failure.message ?: failure::class.simpleName.orEmpty()),
                        level = Telemetry.Level.WARN,
                    )
                }.getOrNull()
    }
}
