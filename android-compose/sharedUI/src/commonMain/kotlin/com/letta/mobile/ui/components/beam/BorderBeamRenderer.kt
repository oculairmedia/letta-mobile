package com.letta.mobile.ui.components.beam

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Corners per outline; [BeamFrame.cornersPx] holds them top-right, bottom-right, bottom-left, top-left. */
internal const val CORNERS = 4

/**
 * One frame of a beam, everything a renderer needs. The beam node keeps one per spec and updates
 * the moving parts in place each frame: [phase] loops 0 to 1, [alpha] is the fade (0 = gone,
 * 1 = fully on) and [cornersPx] are the outline's corner radii in px (top-right, bottom-right,
 * bottom-left, top-left, the order the border is walked clockwise from the right edge), each
 * clamped to half the shorter side.
 */
internal class BeamFrame(
    val style: LettaBeamStyle,
    val direction: LettaBeamDirection,
    val palette: BeamPalette,
) {
    var phase: Float = 0f
    var alpha: Float = 1f
    val cornersPx: FloatArray = FloatArray(CORNERS)
}

/** A palette fitted once per spec: the colours the Canvas fallback samples and the floats the shader takes. */
internal class BeamPalette(colors: List<Color>) {
    val colors: List<Color> = fitBeamPalette(colors)
    val packed: FloatArray = packBeamPalette(colors)
    val count: Int get() = colors.size
}

/**
 * Draws a beam over the whole draw scope. The platform's shader renderer comes from
 * [createBorderBeamShader]; [BorderBeamFallback] draws where none is available.
 */
internal interface BorderBeamRenderer {
    fun DrawScope.drawBeam(frame: BeamFrame)

    /** Frees what the renderer holds natively (desktop's Skia builder and paint); idempotent. */
    fun release() {}
}

/**
 * The beam shader compiled for this platform: AGSL on Android 13 and later, Skia SkSL on desktop
 * and web. Null where runtime shaders do not exist or the source does not compile (logged); the
 * common [BorderBeamFallback] draws then. [areaPx] lets raster platforms select the perimeter
 * renderer before compiling an expensive card-sized shader; zero requests the shader directly.
 */
internal expect fun createBorderBeamShader(areaPx: Float = 0f): BorderBeamRenderer?

internal const val BORDER_BEAM_TELEMETRY_TAG = "BorderBeam"
