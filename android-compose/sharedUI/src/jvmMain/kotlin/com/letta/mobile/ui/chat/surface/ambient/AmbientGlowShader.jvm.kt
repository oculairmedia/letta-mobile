package com.letta.mobile.ui.chat.surface.ambient

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_MAIN_PREMULTIPLIED
import com.letta.mobile.ui.ambient.AMBIENT_GLOW_SHADER_SOURCE
import com.letta.mobile.ui.ambient.AmbientMotion
import com.letta.mobile.util.Telemetry
import kotlin.math.ceil
import org.jetbrains.skia.Canvas as SkiaCanvas
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Paint as SkiaPaint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Surface as SkiaSurface

/**
 * Desktop: the shared ambient source compiled as SkSL, exactly as DesktopAmbientChatBackground
 * compiles it (Skia expects a premultiplied result, hence that main). Compiled once per page
 * (AmbientGlowShaders); the builder and paint are reused across frames and released with it.
 */
internal actual fun createAmbientGlowShader(): AmbientGlowShader? = SkiaAmbientGlowShader.compile()

private class SkiaAmbientGlowShader(private val builder: RuntimeShaderBuilder) : AmbientGlowShader {
    private val paint = SkiaPaint()

    /** The last frame's inputs, and the still frame rendered for them once they stopped changing. */
    private var lastKey: Pair<AmbientGlowUniforms, Size>? = null
    private var still: SkiaImage? = null

    override fun DrawScope.drawGlow(uniforms: AmbientGlowUniforms) {
        val key = uniforms to size
        val repeated = key == lastKey
        if (!repeated) releaseStill()
        lastKey = key
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            // A glow that is not moving (reduced motion, or settled with its clock paused) is
            // rendered once and then blitted: Skia's CPU raster path runs the shader per pixel on
            // every redraw otherwise. A moving glow changes every frame and is drawn directly.
            val image = if (repeated) still ?: renderStill(uniforms) else null
            if (image != null) {
                native.drawImage(image, 0f, 0f)
            } else {
                drawShader(native, uniforms, size.width, size.height)
            }
        }
    }

    private fun renderStill(uniforms: AmbientGlowUniforms): SkiaImage? {
        val (_, size) = lastKey ?: return null
        val width = ceil(size.width).toInt()
        val height = ceil(size.height).toInt()
        if (width <= 0 || height <= 0) return null
        val surface = SkiaSurface.makeRasterN32Premul(width, height)
        drawShader(surface.canvas, uniforms, size.width, size.height)
        val image = surface.makeImageSnapshot()
        surface.close()
        still = image
        return image
    }

    private fun drawShader(canvas: SkiaCanvas, uniforms: AmbientGlowUniforms, width: Float, height: Float) {
        val tint = uniforms.tint
        builder.uniform("uSize", width, uniforms.fieldHeight)
        builder.uniform("uTime", uniforms.phase)
        builder.uniform("uAgitation", uniforms.agitation)
        builder.uniform("uEnvelope", uniforms.envelope)
        builder.uniform("uStreamEnergy", uniforms.streamEnergy)
        builder.uniform("uPalettePull", AmbientMotion.PALETTE_HUE_PULL)
        builder.uniform("uBandTop", uniforms.band.top)
        builder.uniform("uBandPeak", uniforms.band.peak)
        builder.uniform("uColor", tint.red, tint.green, tint.blue, tint.alpha * uniforms.gain)
        // Uniforms bake in at makeShader time, so one native Shader per frame; released as soon
        // as the draw that used it returns rather than left to the cleaner.
        val frameShader = builder.makeShader(null)
        paint.shader = frameShader
        canvas.drawRect(SkiaRect.makeWH(width, height), paint)
        paint.shader = null
        frameShader.close()
    }

    private fun releaseStill() {
        still?.close()
        still = null
    }

    private var released = false

    override fun release() {
        if (released) return
        released = true
        releaseStill()
        paint.shader = null
        paint.close()
        builder.close()
    }

    companion object {
        /** Null when the SkSL does not compile here: loud, so a broken edit cannot ship as a flatter glow. */
        fun compile(): SkiaAmbientGlowShader? =
            runCatching {
                SkiaAmbientGlowShader(
                    RuntimeShaderBuilder(RuntimeEffect.makeForShader(AMBIENT_GLOW_SHADER_SOURCE + AMBIENT_GLOW_MAIN_PREMULTIPLIED)),
                )
            }.onFailure { failure ->
                Telemetry.event(
                    AMBIENT_GLOW_TELEMETRY_TAG,
                    "shader.compileFailed",
                    "error" to (failure.message ?: failure::class.simpleName.orEmpty()),
                    level = Telemetry.Level.WARN,
                )
            }.getOrNull()
    }
}
