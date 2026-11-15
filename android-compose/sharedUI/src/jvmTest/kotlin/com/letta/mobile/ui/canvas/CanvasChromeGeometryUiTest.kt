@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.width
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasStorageFault
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The board's chrome after it moved onto the shared components, at the four widths every UI change
 * is checked at: a 360dp phone, the 700 and 760dp tablet edges either side of the canvas's compact
 * break, and a 1200dp desktop window. On a phone the title and actions pills share one row, so they
 * must fit beside each other; the storage banner grows to a readable line and no wider; and every
 * colour swatch in the board menu is the shared swatch's one size.
 */
class CanvasChromeGeometryUiTest {
    private val widths = listOf(360.dp, 700.dp, 760.dp, 1200.dp)

    private fun ComposeUiTest.showAt(width: Dp, content: @Composable () -> Unit) {
        setContent {
            MaterialTheme {
                // The test window is smaller than a desktop width, so the pane is laid out at its own
                // size from the top-left corner rather than squeezed into the window.
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(width, HEIGHT)) { content() }
            }
        }
    }

    @Test
    fun theTitleAndActionsPillsShareTheTopRowAtEveryWidth() = widths.forEach { width ->
        runComposeUiTest {
            val compact = width < COMPACT_CANVAS_WIDTH
            showAt(width) {
                Row(Modifier.fillMaxWidth()) {
                    CanvasTitlePill(
                        title = "A board with a long title",
                        revision = 3,
                        onNavigateBack = {},
                        modifier = Modifier.testTag(TITLE),
                        compact = compact,
                    )
                    Spacer(Modifier.weight(1f))
                    actionsPill(compact)
                }
            }
            val title = onNodeWithTag(TITLE).getBoundsInRoot()
            val actions = onNodeWithTag(ACTIONS).getBoundsInRoot()
            assertTrue(actions.right <= width, "the actions pill ends inside $width (ends at ${actions.right})")
            assertTrue(title.right <= actions.left, "the title pill stops before the actions pill at $width")
            val pillHeight = LettaDimens.Control.iconButtonLg + LettaDimens.Space.xs * 2
            assertEquals(pillHeight.value, actions.height.value, 0.5f, "the actions pill is one row of buttons at $width")
        }
    }

    @Test
    fun theBoardMenuSwatchesAreTheSharedSwatchAndStayOnScreen() = widths.forEach { width ->
        runComposeUiTest {
            showAt(width) { actionsPill(compact = width < COMPACT_CANVAS_WIDTH) }
            onNodeWithContentDescription("More").performClick()
            BoardBackgrounds.forEach { entry ->
                val swatch = onNodeWithContentDescription("Background ${entry.name}").getBoundsInRoot()
                assertEquals(LettaComponentDimens.swatch.value, swatch.width.value, 0.5f, "${entry.name} swatch at $width")
                assertTrue(swatch.left >= 0.dp && swatch.right <= width, "${entry.name} swatch inside $width")
            }
        }
    }

    @Test
    fun theOverflowMenuIsTheSharedRowsAndChipsAndStaysOnScreen() = widths.forEach { width ->
        runComposeUiTest {
            showAt(width) { actionsPill(compact = width < COMPACT_CANVAS_WIDTH) }
            onNodeWithContentDescription("More").performClick()
            val rows = listOf("Export JSON", "Export SVG", "Clear").map { onNodeWithText(it).getBoundsInRoot() }
            rows.forEach { row -> assertEquals(rows.first().height.value, row.height.value, 0.5f, "one menu row height at $width") }
            val chips = listOf("Pattern none", "Pattern dots", "Pattern grid").mapNotNull { description ->
                runCatching { onNodeWithContentDescription(description).getBoundsInRoot() }.getOrNull()
            }
            assertTrue(chips.isNotEmpty(), "the background pattern chips are shown at $width")
            chips.forEach { chip ->
                assertEquals(chips.first().height.value, chip.height.value, 0.5f, "one chip height at $width")
                assertTrue(chip.left >= 0.dp && chip.right <= width, "chip $chip inside $width")
            }
        }
    }

    @Test
    fun theStorageBannerGrowsToAReadableLineAndNoWider() = widths.forEach { width ->
        runComposeUiTest {
            showAt(width) { CanvasStorageFaultBanner(listOf(fault), modifier = Modifier.testTag(BANNER)) }
            val banner = onNodeWithTag(BANNER).getBoundsInRoot()
            assertEquals(min(width, LettaDimens.Pane.noticeMaxWidth).value, banner.width.value, 0.5f, "banner width at $width")
        }
    }

    @Test
    fun theLoadFailureCentresItsRetryAtEveryWidth() = widths.forEach { width ->
        runComposeUiTest {
            showAt(width) { CanvasWorkspaceLoadFailure(Modifier, onRetry = {}) }
            val page = onNodeWithTag(CANVAS_WORKSPACE_LOAD_FAILURE_TAG).getBoundsInRoot()
            assertEquals(width.value, page.width.value, 0.5f, "the failure spans the pane at $width")
            val middle = (page.top + page.bottom) / 2
            assertEquals((HEIGHT / 2).value, middle.value, 0.5f, "the failure is centred in the pane at $width")
        }
    }

    @Composable
    private fun actionsPill(compact: Boolean) {
        CanvasActionsPill(
            zoom = CanvasZoom(scalePercent = 100, onZoomOut = {}, onZoomIn = {}, onReset = {}),
            checkpointCount = 2,
            onHistory = {},
            onShare = {},
            menu = CanvasMenuActions({}, {}, {}, {}, {}),
            background = CanvasBackgroundActions(Color.White, {}, CanvasBackgroundPattern(), {}),
            modifier = Modifier.testTag(ACTIONS),
            undo = if (compact) CanvasUndoActions(canUndo = true, canRedo = false, onUndo = {}, onRedo = {}) else null,
        )
    }

    private companion object {
        val HEIGHT = 600.dp
        const val TITLE = "geometry-title"
        const val ACTIONS = "geometry-actions"
        const val BANNER = "geometry-banner"
        val fault = CanvasStorageFault(
            kind = CanvasStorageFault.Kind.SAVE_FAILED,
            documentId = "1d0c78f4",
            canvasId = null,
            documentBytes = 62_600_352,
            message = "put failed",
            atEpochMs = 0L,
        )
    }
}
