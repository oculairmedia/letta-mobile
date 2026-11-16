package com.letta.mobile.ui.components.beam

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Desktop and web (both Skiko): the shared beam source compiled as SkSL. The effect is compiled
 * once per process and shared; each beam owns a builder (its uniforms) and a paint, released with it.
 */
internal actual fun createBorderBeamShader(): BorderBeamRenderer? =
    BorderBeamEffect.effect?.let { SkiaBorderBeam(RuntimeShaderBuilder(it)) }

/** The compiled effect, or null when the SkSL does not compile here (logged once). */
private object BorderBeamEffect {
    val effect: RuntimeEffect? by lazy {
        runCatching { RuntimeEffect.makeForShader(BORDER_BEAM_SHADER) }
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

private class SkiaBorderBeam(private val builder: RuntimeShaderBuilder) : BorderBeamRenderer {
    private val paint = SkiaPaint()
    private var released = false

    override fun DrawScope.drawBeam(frame: BeamFrame) {
        val uniforms = BeamUniforms(this, frame)
        builder.uniform("size", uniforms.width, uniforms.height)
        builder.uniform("density", uniforms.density)
        builder.uniform("cornerRadius", uniforms.cornerRadius)
        builder.uniform("phase", uniforms.phase)
        builder.uniform("direction", uniforms.direction)
        builder.uniform("mode", uniforms.mode)
        builder.uniform("arc", uniforms.arc)
        builder.uniform("glow", uniforms.glow)
        builder.uniform("intensity", uniforms.intensity)
        builder.uniform("count", uniforms.count)
        builder.uniform("colors", uniforms.colors)
        val width = size.width
        val height = size.height
        drawIntoCanvas { canvas ->
            // Uniforms bake in at makeShader time, so one native shader per frame, closed as soon
            // as the draw that used it returns rather than left to the cleaner.
            val frameShader = builder.makeShader(null)
            paint.shader = frameShader
            canvas.nativeCanvas.drawRect(SkiaRect.makeWH(width, height), paint)
            paint.shader = null
            frameShader.close()
        }
    }

    override fun release() {
        if (released) return
        released = true
        paint.close()
        builder.close()
    }
}
