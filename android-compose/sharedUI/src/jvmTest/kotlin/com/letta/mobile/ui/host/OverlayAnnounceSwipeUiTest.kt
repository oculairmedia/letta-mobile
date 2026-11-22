@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.host

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Overlays are announced (a snackbar politely, an error banner at once), a touch user can swipe a
 * snackbar away (its dismissal runs, as on timeout), a pointer host has no swipe, a screen reader can
 * always dismiss it, and a newer overlay with the same key updates the one on screen in place.
 */
class OverlayAnnounceSwipeUiTest {
    private val host = OverlayHost(clock = { 0L })

    private fun ComposeUiTest.showLayer(input: LettaInput) = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalLettaHost provides LettaHost(input = input), LocalReducedMotion provides true) {
                Box(Modifier.size(width = 600.dp, height = 400.dp)) { OverlayHostLayer(host, Modifier.size(600.dp, 400.dp)) }
            }
        }
    }

    private fun liveRegion(mode: LiveRegionMode) = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)

    @Test
    fun aSnackbarIsAnnouncedPolitelyAndAnErrorBannerAtOnce() = runComposeUiTest {
        host.show(Overlay.Snackbar("Saved"))
        host.show(Overlay.Banner("Runtime is down", presentation = OverlayPresentation.Banner.copy(tone = OverlayTone.Error)))
        showLayer(LettaInput.Pointer)

        onNodeWithTag(OverlayTags.SNACKBAR).assert(liveRegion(LiveRegionMode.Polite))
        onNodeWithTag(OverlayTags.BANNER).assert(liveRegion(LiveRegionMode.Assertive))
    }

    @Test
    fun aTouchUserSwipesASnackbarAwayAndItsDismissalRuns() = runComposeUiTest {
        var committed = false
        host.show(Overlay.undo("Chat deleted", key = "undo", onUndo = {}, onCommit = { committed = true }))
        showLayer(LettaInput.Touch)

        onNodeWithTag(OverlayTags.SNACKBAR).performTouchInput { swipeLeft() }
        waitUntil(timeoutMillis = WAIT_MILLIS) { host.snackbar == null }
        assertTrue(committed, "swiping an undo away commits the change, as its timeout would")
    }

    @Test
    fun aPointerHostHasNoSwipeButAScreenReaderCanDismiss() = runComposeUiTest {
        var dismissed = false
        host.show(Overlay.Snackbar("Copied", response = OverlayResponse(onDismissed = { dismissed = true })))
        showLayer(LettaInput.Pointer)

        onNodeWithTag(OverlayTags.SNACKBAR).performTouchInput { swipeLeft() }
        waitForIdle()
        assertFalse(dismissed, "a pointer host has no swipe")

        onNodeWithTag(OverlayTags.SNACKBAR).performSemanticsAction(SemanticsActions.Dismiss)
        waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun aNewerOverlayWithTheSameKeyUpdatesTheOneOnScreen() = runComposeUiTest {
        host.show(Overlay.Snackbar("Uploading 1 of 3", key = "upload", presentation = OverlayPresentation.Sticky))
        showLayer(LettaInput.Pointer)
        host.show(Overlay.Snackbar("Uploading 2 of 3", key = "upload", presentation = OverlayPresentation.Sticky))
        waitForIdle()

        assertEquals(1, onAllNodesWithTag(OverlayTags.SNACKBAR).fetchSemanticsNodes().size)
        val inSnackbar = hasAnyAncestor(hasTestTag(OverlayTags.SNACKBAR))
        assertEquals(1, onAllNodes(hasText("Uploading 2 of 3") and inSnackbar).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodes(hasText("Uploading 1 of 3") and inSnackbar).fetchSemanticsNodes().size)
        assertTrue(host.waiting.isEmpty())
    }

    private val errorBanner = OverlayPresentation.Banner.copy(tone = OverlayTone.Error)

    private fun snapshot(width: Dp, dark: Boolean, input: LettaInput) = runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) {
        val own = OverlayHost(clock = { 0L })
        own.show(Overlay.undo("Chat deleted", key = "undo", onUndo = {}, onCommit = {}))
        own.show(Overlay.Banner("The agent runtime is not responding.", presentation = errorBanner))
        setContent {
            SharedMaterialTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalLettaHost provides LettaHost(input = input), LocalReducedMotion provides true) {
                    Box(Modifier.size(width, FRAME_HEIGHT).background(MaterialTheme.colorScheme.background).testTag(FRAME)) {
                        OverlayHostLayer(own, Modifier.matchParentSize())
                    }
                }
            }
        }
        waitForIdle()
        val out = File("build/overlay-snapshots").apply { mkdirs() }
            .resolve("overlay-${input.name.lowercase()}-${if (dark) "dark" else "light"}-${width.value.toInt()}dp.png")
        ImageIO.write(onNodeWithTag(FRAME).captureToImage().toAwtImage(), "png", out)
        assertTrue(out.length() > 0)
    }

    @Test
    fun overlaysAtEveryWidth() = WIDTHS.forEach { width ->
        listOf(false, true).forEach { dark -> snapshot(width, dark, if (width < 700.dp) LettaInput.Touch else LettaInput.Pointer) }
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val FRAME = "overlay-frame"
        const val WINDOW_WIDTH = 1300
        const val WINDOW_HEIGHT = 500
        val FRAME_HEIGHT = 320.dp
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
    }
}
