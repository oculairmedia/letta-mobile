@file:OptIn(ExperimentalTestApi::class, ExperimentalLayoutApi::class)

package com.letta.mobile.ui.components.beam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.ui.theme.tokens.LettaBeamPalette
import com.letta.mobile.ui.theme.tokens.LettaBeamTone
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The border beam on desktop: its Skia shader compiles and lights the border, the Canvas fallback
 * does too, the beam moves only when it should, and the gallery renders at the four reference
 * widths in both themes under build/design-system-render/ (review artifacts, not pixel goldens).
 */
class LettaBorderBeamRenderTest {
    @Test
    fun theSkiaShaderCompiles() {
        val shader = createBorderBeamShader()
        assertNotNull(shader, "the SkSL source compiles on desktop")
        shader.release()
    }

    @Test
    fun anActiveBeamLightsTheBorderAndLeavesTheMiddleAlone() = runDesktopComposeUiTest(width = BOX_W, height = BOX_H) {
        mainClock.autoAdvance = false
        setContent { BeamBox(active = true, reducedMotion = true) }
        waitForIdle()
        val pixels = onRoot().captureToImage().toPixelMap()
        assertTrue(pixels.edgeIsLit(), "the beam draws along the border")
        assertTrue(pixels[BOX_W / 2, BOX_H / 2].isBlack(), "the middle keeps the container's own fill")
    }

    @Test
    fun anInactiveBeamDrawsNothing() = runDesktopComposeUiTest(width = BOX_W, height = BOX_H) {
        mainClock.autoAdvance = false
        setContent { BeamBox(active = false, reducedMotion = false) }
        waitForIdle()
        assertFalse(onRoot().captureToImage().toPixelMap().edgeIsLit(), "nothing is drawn while off")
    }

    @Test
    fun switchingOffFadesTheBeamOut() = runDesktopComposeUiTest(width = BOX_W, height = BOX_H) {
        mainClock.autoAdvance = false
        var active by mutableStateOf(true)
        setContent { BeamBox(active = active, reducedMotion = false) }
        waitForIdle()
        active = false
        mainClock.advanceTimeBy(FADE_PAST_MS)
        waitForIdle()
        assertFalse(onRoot().captureToImage().toPixelMap().edgeIsLit(), "gone once the fade has run")
    }

    @Test
    fun theBeamMovesUnlessMotionIsReduced() {
        assertTrue(framesDiffer(reducedMotion = false), "the beam travels between frames")
        assertFalse(framesDiffer(reducedMotion = true), "under reduced motion the beam stays put")
    }

    @Test
    fun theCanvasFallbackLightsTheBorderToo() {
        val bitmap = ImageBitmap(BOX_W, BOX_H)
        val frame = BeamFrame(LettaBeamStyle.Orbit, LettaBeamDirection.Clockwise, BeamPalette(LettaBeamPalette.spectrum))
        frame.phase = 0.4f
        frame.cornerRadiusPx = 16f
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(BOX_W.toFloat(), BOX_H.toFloat())) {
            with(BorderBeamFallback()) { drawBeam(frame) }
        }
        val pixels = bitmap.toPixelMap()
        assertTrue(pixels.edgeIsLit(), "the fallback strokes the border")
        assertTrue(pixels[BOX_W / 2, BOX_H / 2].alpha == 0f, "and leaves the middle clear")
    }

    @Test
    fun galleryRendersAtEveryReferenceWidthInBothThemes() = listOf(true, false).forEach { dark ->
        REFERENCE_WIDTHS.forEach { width ->
            runDesktopComposeUiTest(width = width, height = GALLERY_H) {
                mainClock.autoAdvance = false
                setContent {
                    SharedMaterialTheme(darkTheme = dark) {
                        CompositionLocalProvider(LocalLettaReducedMotion provides true) { BeamGallery() }
                    }
                }
                waitForIdle()
                val theme = if (dark) "dark" else "light"
                val out = File("build/design-system-render").apply { mkdirs() }.resolve("border-beam-$theme-$width.png")
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
                assertTrue(out.length() > 0, "rendered ${out.name}")
            }
        }
    }

    private fun framesDiffer(reducedMotion: Boolean): Boolean {
        var differ = false
        runDesktopComposeUiTest(width = BOX_W, height = BOX_H) {
            mainClock.autoAdvance = false
            setContent { BeamBox(active = true, reducedMotion = reducedMotion) }
            waitForIdle()
            val before = onRoot().captureToImage().toPixelMap()
            mainClock.advanceTimeBy(MOVE_MS)
            waitForIdle()
            val after = onRoot().captureToImage().toPixelMap()
            differ = (0 until BOX_W).any { x -> before[x, 0] != after[x, 0] || before[x, BOX_H - 1] != after[x, BOX_H - 1] }
        }
        return differ
    }

    @Composable
    private fun BeamBox(active: Boolean, reducedMotion: Boolean) {
        CompositionLocalProvider(LocalLettaReducedMotion provides reducedMotion) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black)
                    .lettaBorderBeam(LettaBeamSpec(LettaBeamPalette.spectrum, RectangleShape), active = active),
            )
        }
    }

    private fun PixelMap.edgeIsLit(): Boolean {
        val top = (0 until width).any { x -> this[x, 1].isLit() }
        val bottom = (0 until width).any { x -> this[x, height - 2].isLit() }
        val sides = (0 until height).any { y -> this[1, y].isLit() || this[width - 2, y].isLit() }
        return top || bottom || sides
    }

    private fun Color.isLit(): Boolean = alpha > 0f && maxOf(red, green, blue) > LIT

    private fun Color.isBlack(): Boolean = maxOf(red, green, blue) < LIT

    private companion object {
        val REFERENCE_WIDTHS = listOf(360, 700, 760, 1200)
        const val BOX_W = 200
        const val BOX_H = 100
        const val GALLERY_H = 640
        const val FADE_PAST_MS = 1_000L
        const val MOVE_MS = 400L
        const val LIT = 0.2f
    }
}

/** Every style, tone and shape of the beam on one surface. */
@Composable
private fun BeamGallery() {
    val scheme = MaterialTheme.colorScheme
    val card = MaterialTheme.shapes.large
    val pill = MaterialTheme.shapes.extraLarge
    Surface(Modifier.fillMaxSize(), color = scheme.background) {
        Column(Modifier.padding(LettaDimens.Space.lg), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg)) {
            Text("Border beam", style = MaterialTheme.typography.titleMedium, color = scheme.onBackground)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
            ) {
                BeamCard("Working · Orbit", "Agent is running", LettaBeamSpec(LettaBeamDefaults.colors(), card))
                BeamCard("Spectrum · Orbit", "Featured", LettaBeamSpec(LettaBeamDefaults.colors(LettaBeamTone.Spectrum), card))
                BeamCard(
                    "Attention · counter-clockwise",
                    "Needs approval",
                    LettaBeamSpec(LettaBeamDefaults.colors(LettaBeamTone.Attention), card, direction = LettaBeamDirection.CounterClockwise),
                )
            }
            Surface(
                shape = pill,
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).height(48.dp)
                    .lettaBorderBeam(LettaBeamSpec(LettaBeamDefaults.colors(), pill, LettaBeamStyle.Sweep)),
            ) {
                Row(Modifier.padding(horizontal = LettaDimens.Space.lg), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LettaIcons.Search, contentDescription = null, tint = scheme.onSurfaceVariant)
                    Text("  Sweep · composer while streaming", color = scheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg), verticalAlignment = Alignment.CenterVertically) {
                listOf(LettaBeamTone.Working, LettaBeamTone.Spectrum, LettaBeamTone.Attention).forEach { tone ->
                    Surface(
                        shape = CircleShape,
                        color = scheme.surfaceContainerHigh,
                        modifier = Modifier.size(48.dp)
                            .lettaBorderBeam(LettaBeamSpec(LettaBeamDefaults.colors(tone), CircleShape, LettaBeamStyle.OrbitCompact)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(LettaIcons.Sparkles, contentDescription = null, tint = scheme.onSurface)
                        }
                    }
                }
                Text("OrbitCompact", color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BeamCard(title: String, body: String, spec: LettaBeamSpec) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = spec.shape,
        color = scheme.surfaceContainer,
        modifier = Modifier.widthIn(min = 300.dp, max = 340.dp).height(120.dp).lettaBorderBeam(spec),
    ) {
        Column(Modifier.padding(LettaDimens.Space.lg), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}
