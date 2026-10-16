package com.letta.mobile.desktop.data

import com.letta.mobile.data.canvas.bubble.CanvasBubbleLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopCanvasBubbleLayoutStoreTest {
    private val directory: Path = Files.createTempDirectory("canvas-bubble")
    private val path: Path = directory.resolve("canvas-bubble-layout.properties")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun aMissingFileIsTheDesktopDefault() {
        assertEquals(CanvasBubbleLayout.Desktop, DesktopCanvasBubbleLayoutStore(path).load())
    }

    @Test
    fun aSavedLayoutRoundTrips() {
        val layout = CanvasBubbleLayout(mascotSizeDp = 96f, composerWidthDp = 820f, replyHoldSeconds = 12f, composerHeightDp = 120f)
        DesktopCanvasBubbleLayoutStore(path).save(layout)
        assertEquals(layout, DesktopCanvasBubbleLayoutStore(path).load())
    }

    @Test
    fun damagedValuesFallBackToTheDefaultsInRange() {
        path.writeText("canvas.bubble.mascotSizeDp=big\ncanvas.bubble.composerWidthDp=10\n")
        assertEquals(
            CanvasBubbleLayout.Desktop.copy(composerWidthDp = CanvasBubbleLayout.COMPOSER_MIN_DP),
            DesktopCanvasBubbleLayoutStore(path).load(),
        )
    }
}
