package com.letta.mobile.ui.chat.surface.ambient

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Desktop and web (both Skiko): the shared wave source compiled as SkSL with the premultiplying
 * main, as the thinking glow is (AmbientGlowShader.skiko.kt). The builder and paint are reused
 * across frames and released with the page's [VoiceWaveShaders].
 */
internal actual fun createVoiceWaveShader(): VoiceWaveShader? = SkiaVoiceWaveShader.compile()

private class SkiaVoiceWaveShader(private val builder: RuntimeShaderBuilder) : VoiceWaveShader {
    private val paint = SkiaPaint()
    private var released = false

    override fun DrawScope.drawWave(frame: VoiceWaveFrame) {
        if (released) return
        val width = size.width
        val height = size.height
        builder.uniform("iResolution", width, height)
        builder.uniform("iTime", frame.time)
        builder.uniform("amplitude", frame.amplitude)
        builder.uniform("pOffset", frame.noiseOffset)
        builder.color("bgColor", frame.background)
        builder.color("c1", frame.c1)
        builder.color("c2", frame.c2)
        builder.color("c3", frame.c3)
        builder.color("c4", frame.c4)
        drawIntoCanvas { canvas ->
            // Uniforms bake in at makeShader time: one native shader per frame, closed right after.
            val frameShader = builder.makeShader(null)
            paint.shader = frameShader
            canvas.skiaCanvas.drawRect(SkiaRect.makeWH(width, height), paint)
            paint.shader = null
            frameShader.close()
        }
    }

    override fun release() {
        if (released) return
        released = true
        paint.shader = null
        paint.close()
        builder.close()
    }

    companion object {
        /** Null when the SkSL does not compile here: loud, so a broken edit cannot ship as a flat band. */
        fun compile(): SkiaVoiceWaveShader? =
            runCatching {
                SkiaVoiceWaveShader(RuntimeShaderBuilder(RuntimeEffect.makeForShader(VOICE_WAVE_SHADER_SOURCE + VOICE_WAVE_MAIN_PREMULTIPLIED)))
            }.onFailure { failure ->
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
private fun RuntimeShaderBuilder.color(name: String, color: Color) = uniform(name, color.red, color.green, color.blue, color.alpha)
