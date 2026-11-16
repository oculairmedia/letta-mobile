package com.letta.mobile.ui.components.beam

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** A card-sized software canvas must fit a frame budget, rather than shade every pixel with 24 blobs. */
class BorderBeamPerformanceTest {
    @Test
    fun fallbackBoundsStrokeCommandsAtFloorDeviceDensity() {
        val renderer = BorderBeamFallback()
        val bitmap = ImageBitmap(WIDTH, HEIGHT)
        val delegate = Canvas(bitmap)
        var commands = 0
        val canvas = Proxy.newProxyInstance(Canvas::class.java.classLoader, arrayOf(Canvas::class.java)) { _, method, args ->
            if (method.name.startsWith("draw")) commands++
            method.invoke(delegate, *(args ?: emptyArray()))
        } as Canvas
        val scope = CanvasDrawScope()
        for (style in LettaBeamStyle.entries) {
            val frame = BeamFrame(style, LettaBeamDirection.Clockwise, BeamPalette(listOf(Color.White)))
            frame.cornersPx.fill(56f)
            repeat(60) { index ->
                frame.phase = index / 60f
                commands = 0
                scope.draw(Density(3.5f), LayoutDirection.Ltr, canvas, Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
                    with(renderer) { drawBeam(frame) }
                }
                assertTrue(commands <= 3, "$style phase ${frame.phase}: $commands strokes exceeds 3")
            }
        }
    }

    @Test
    fun cardRenderingFitsTheFrameBudget() {
        val renderer = assertNotNull(createBorderBeamShader())
        val bitmap = ImageBitmap(WIDTH, HEIGHT)
        val canvas = Canvas(bitmap)
        val scope = CanvasDrawScope()
        val frame = BeamFrame(LettaBeamStyle.Orbit, LettaBeamDirection.Clockwise, BeamPalette(listOf(Color.White)))
        frame.cornersPx.fill(16f)
        fun draw() = scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
            with(renderer) { drawBeam(frame) }
        }
        try {
            repeat(WARMUP_FRAMES) { draw() }
            val start = TimeSource.Monotonic.markNow()
            repeat(MEASURED_FRAMES) {
                frame.phase = it.toFloat() / MEASURED_FRAMES
                draw()
            }
            val elapsed = start.elapsedNow().inWholeMilliseconds
            println("$MEASURED_FRAMES card frames: ${elapsed}ms (budget ${BUDGET_MS}ms)")
            assertTrue(elapsed < BUDGET_MS, "$MEASURED_FRAMES card frames took ${elapsed}ms (budget ${BUDGET_MS}ms)")
        } finally {
            renderer.release()
        }
    }

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 200
        const val WARMUP_FRAMES = 2
        const val MEASURED_FRAMES = 10
        // Deliberately loose for shared CI hosts; old per-pixel glow takes several seconds.
        const val BUDGET_MS = 500L
    }
}
