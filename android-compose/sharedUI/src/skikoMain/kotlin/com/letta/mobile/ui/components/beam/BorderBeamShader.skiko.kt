package com.letta.mobile.ui.components.beam

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * Desktop and web (both Skiko): the shared beam source compiled as SkSL. The effect is compiled
 * once per process and shared; each beam owns a builder (its uniforms) and a paint, released with it.
 */
internal actual fun createBorderBeamShader(areaPx: Float): BorderBeamRenderer? =
    if (areaPx > MAX_SHADER_AREA_PX) BorderBeamFallback()
    else BorderBeamEffect.effect?.let { SkiaBorderBeam(RuntimeShaderBuilder(it)) }

/** The compiled effect, or null when the SkSL does not compile here (logged once). */
private object BorderBeamEffect {
    val effect: RuntimeEffect? by lazy {
        compileBeamShader { RuntimeEffect.makeForShader(BORDER_BEAM_SHADER) }
    }
}

private class SkiaBorderBeam(private val builder: RuntimeShaderBuilder) : BorderBeamRenderer {
    private val paint = SkiaPaint()
    private var released = false
    private val pathRenderer = BorderBeamFallback()

    override fun DrawScope.drawBeam(frame: BeamFrame) {
        // RuntimeEffect is also evaluated on software canvases. Its 24 Gaussian samples per
        // pixel are appropriate for small controls, but card-sized passes stall raster rendering.
        // Bound that cost in production, using the same perimeter renderer as older phones.
        if (size.width * size.height > MAX_SHADER_AREA_PX) {
            with(pathRenderer) { drawBeam(frame) }
            return
        }
        val uniforms = BeamUniforms(this, frame)
        builder.uniform("size", uniforms.width, uniforms.height)
        builder.uniform("density", uniforms.density)
        val radii = uniforms.radii
        builder.uniform("radii", radii[0], radii[1], radii[2], radii[3])
        builder.uniform("phase", uniforms.phase)
        builder.uniform("direction", uniforms.direction)
        builder.uniform("mode", uniforms.mode)
        builder.uniform("arc", uniforms.arc)
        builder.uniform("glow", uniforms.glow)
        builder.uniform("widthScale", uniforms.widthScale)
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

/** Above a small control's area, raster cost must scale with its perimeter instead of its fill. */
private const val MAX_SHADER_AREA_PX = 64 * 64
