@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.ui.canvas.LocalCanvasCompact
import io.ak1.drawbox.domain.model.Viewport
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * letta-mobile-s416w.4: the fallback cards of the `canvas/plugin/v1/fallback-card.json` fixture on a
 * board, at a desktop size in the Pointer style and at phone size (412 x 915 dp, drawn at 2x) in the
 * Touch style, light and dark: a running job with its snapshot, an uninstalled plugin offline, and a
 * newer kind whose snapshot is still on its way. The first one is selected. Writes PNGs to
 * build/canvas-plugin/fallback-card-*.png for a reviewer; it asserts that each rendered with its
 * snapshot in place.
 */
class CanvasPluginSnapshotTest {
    private class Shot(val name: String, val phone: Boolean, val dark: Boolean)

    private fun snapshot(shot: Shot) {
        val width = if (shot.phone) PHONE_WIDTH_DP * SCALE else DESKTOP_WIDTH
        val height = if (shot.phone) PHONE_HEIGHT_DP * SCALE else DESKTOP_HEIGHT
        runDesktopComposeUiTest(width = width, height = height) {
            val fixture = PluginCardFixtures.board()
            // The phone zooms out a little, so the row of three cards fits its width, and sits it mid-screen.
            val viewport = if (shot.phone) Viewport(offset = Offset(0f, PHONE_PAN_Y), scale = PHONE_ZOOM) else Viewport()
            setContent {
                val density = if (shot.phone) Density(SCALE.toFloat(), 1f) else LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides density,
                    LocalCanvasCompact provides shot.phone,
                    LocalPluginAvailability provides fixture.availability,
                ) {
                    MaterialTheme(colorScheme = if (shot.dark) darkColorScheme() else lightColorScheme()) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            val doc by fixture.session.document.collectAsState()
                            CanvasPluginLayer(
                                board = rememberPluginBoard(fixture.session, doc, fixture.assets),
                                viewport = viewport,
                                selection = PluginBoardSelection(selectedId = "pe-render"),
                            )
                        }
                    }
                }
            }
            waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag(CanvasPluginTestTags.snapshot("pe-render")).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            val image = onRoot().captureToImage().toAwtImage()
            val out = File("build/canvas-plugin").apply { mkdirs() }.resolve("${shot.name}.png")
            ImageIO.write(image, "png", out)
            assertTrue(out.length() > 0)
        }
    }

    @Test
    fun desktopLight() = snapshot(Shot("fallback-card-desktop-light", phone = false, dark = false))

    @Test
    fun desktopDark() = snapshot(Shot("fallback-card-desktop-dark", phone = false, dark = true))

    @Test
    fun phoneLight() = snapshot(Shot("fallback-card-phone-light", phone = true, dark = false))

    @Test
    fun phoneDark() = snapshot(Shot("fallback-card-phone-dark", phone = true, dark = true))

    private companion object {
        const val DESKTOP_WIDTH = 1100
        const val DESKTOP_HEIGHT = 400
        const val PHONE_WIDTH_DP = 412
        const val PHONE_HEIGHT_DP = 915
        const val SCALE = 2
        const val PHONE_ZOOM = 0.75f
        const val PHONE_PAN_Y = 640f
    }
}
