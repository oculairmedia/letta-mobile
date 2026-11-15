@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.ui.settings.BackendConnectionStatusRow
import com.letta.mobile.ui.settings.SettingsSearchField
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height

/**
 * The older shared surfaces rebuilt on the foundation components (the catalog card and info box on
 * LettaPanel, the side pane's close on LettaIconButton, the settings search on LettaSearchField, the
 * backend status on LettaStatusLabel) keep their geometry at the owner's four widths: each fills the
 * width it is given, keeps its controls inside it, and gives every control the platform's touch target.
 */
class SharedSurfaceGeometryTest {
    @Test
    fun aCatalogCardFillsItsWidthAndIsOneButton() = atEveryWidth { width ->
        var opened = 0
        show(width) {
            LettaCatalogCard(
                title = "A catalog entry whose title is long enough to need ellipsizing on a phone",
                description = "Two lines are reserved for the description so cards in a grid row line up.",
                accent = Color(ACCENT),
                onClick = { opened++ },
                modifier = Modifier.fillMaxWidth().testTag(CARD),
                trailing = { Box(Modifier.width(LettaControlTokens.iconButton).testTag(TRAILING)) },
            )
        }
        onNodeWithTag(CARD).assertWidthIsEqualTo(width)
        assertInside(TRAILING, width)
        onNodeWithText("A catalog entry", substring = true).performClick()
        assertEquals(1, opened, "the whole card opens its entry at $width")
        val button = onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and hasAnyAncestor(hasTestTag(CARD)))
        assertTrue(button.getBoundsInRoot().height >= LettaControlTokens.minTouchTarget, "the card's row is a touch target at $width")
    }

    @Test
    fun anInfoBoxFillsItsWidthInsideItsPadding() = atEveryWidth { width ->
        show(width) { LettaInfoBox("Nothing here yet", modifier = Modifier.testTag(INFO)) }
        onNodeWithTag(INFO).assertWidthIsEqualTo(width)
        assertInside("Nothing here yet", width, byText = true)
    }

    @Test
    fun aSidePaneHeaderKeepsItsCloseButtonInsideAndTappable() = atEveryWidth { width ->
        var closed = 0
        show(width) { LettaSidePaneHeader(title = "Background tasks with a long enough title to ellipsize", onClose = { closed++ }) }
        val close = onNodeWithContentDescription("Close").getBoundsInRoot()
        assertTrue(close.right <= width, "close stays inside $width (right ${close.right})")
        assertTrue(close.width >= LettaControlTokens.iconButton, "close keeps its button size at $width")
        onNodeWithContentDescription("Close").performClick()
        assertEquals(1, closed)
    }

    @Test
    fun theSettingsSearchFieldFillsItsWidth() = atEveryWidth { width ->
        show(width) { SettingsSearchField(query = "", onQueryChange = {}) }
        onNodeWithTag(com.letta.mobile.ui.settings.SettingsPageTags.SEARCH).assertWidthIsEqualTo(width)
    }

    @Test
    fun theBackendStatusRowStaysOnOneLineInsideItsWidth() = atEveryWidth { width ->
        show(width) { BackendConnectionStatusRow(BackendConnectionStatus.Connecting) }
        val row = onNodeWithTag(com.letta.mobile.ui.settings.BackendConnectionTags.STATUS).getBoundsInRoot()
        assertTrue(row.right <= width, "status stays inside $width")
        assertTrue(row.height <= LettaControlTokens.minTouchTarget, "status is one line at $width")
    }

    private fun atEveryWidth(check: ComposeUiTest.(Dp) -> Unit) {
        WIDTHS.forEach { width -> runDesktopComposeUiTest(WINDOW_WIDTH, WINDOW_HEIGHT) { check(width) } }
    }

    private fun ComposeUiTest.show(width: Dp, content: @Composable () -> Unit) {
        setContent {
            SharedMaterialTheme(darkTheme = true) {
                Surface { Box(Modifier.width(width)) { content() } }
            }
        }
    }

    private fun ComposeUiTest.assertInside(key: String, width: Dp, byText: Boolean = false) {
        // The unmerged tree: a part inside a clickable row merges into the row in the merged one.
        val node = if (byText) onNodeWithText(key, useUnmergedTree = true) else onNodeWithTag(key, useUnmergedTree = true)
        val bounds = node.getBoundsInRoot()
        assertTrue(bounds.left >= 0.dp && bounds.right <= width, "$key stays inside $width (${bounds.left}..${bounds.right})")
    }

    private companion object {
        /** The owner's four widths: a phone, the two sides of the tablet breakpoint, a desktop window. */
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
        const val WINDOW_WIDTH = 1400
        const val WINDOW_HEIGHT = 900
        const val ACCENT = 0xFF6750A4
        const val CARD = "catalog-card"
        const val TRAILING = "catalog-trailing"
        const val INFO = "info-box"
    }
}
