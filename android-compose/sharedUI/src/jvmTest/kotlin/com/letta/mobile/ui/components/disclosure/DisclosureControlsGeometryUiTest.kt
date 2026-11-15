@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.disclosure

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The one disclosure line and the menu indicator keep the sizes their callers had before they were
 * merged onto them, from the 360 dp phone to a 1200 dp window: a standalone header is at least the touch
 * minimum, its chevron closes the line at the frame's edge, a padded header (a provider
 * card's) keeps its inset inside the toggle, and the menu indicator is the small glyph unless told
 * otherwise.
 */
class DisclosureControlsGeometryUiTest {

    @Test
    fun aHeaderKeepsTheTouchMinimumAndEndsInTheChevron() = atEveryWidth { width ->
        var flips = 0
        show(width) {
            Column {
                Box(Modifier.testTag(PLAIN)) { Text(LABEL, maxLines = 1) }
                Box(Modifier.testTag(HEADER)) {
                    LettaDisclosureHeader(expanded = false, onToggle = { flips++ }) { Text(LABEL, Modifier.weight(1f), maxLines = 1) }
                }
            }
        }
        val chevron = onAllNodesWithTag(DisclosureChevronDefaults.TestTag, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        assertNear(maxOf(height(PLAIN), LettaControlTokens.minTouchTarget), height(HEADER))
        assertNear(width, chevron.right)
        assertNear(LettaControlTokens.icon, chevron.right - chevron.left)
        onNodeWithTag(HEADER).assertExists()
        onAllNodesWithTag(DisclosureChevronDefaults.TestTag, useUnmergedTree = true)[0].performClick()
        assertEquals(1, flips)
    }

    @Test
    fun aPaddedHeaderKeepsItsInsetInsideTheToggleAndTheSmallChevron() = atEveryWidth { width ->
        val style = DisclosureRowStyle(contentPadding = PaddingValues(LettaSpaceTokens.md), compactChevron = true)
        show(width) {
            Box(Modifier.testTag(HEADER)) {
                DisclosureRow(LineDisclosure(expanded = true, onToggle = {}), Modifier.testTag(ROW), style = style) {
                    Text(LABEL, Modifier.weight(1f), maxLines = 1)
                }
            }
        }
        val chevron = onNodeWithTag(DisclosureChevronDefaults.TestTag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val row = bounds(ROW)
        assertNear(row.right - LettaSpaceTokens.md, chevron.right)
        assertNear(LettaControlTokens.iconSm, chevron.right - chevron.left)
        assertNear(row.right - row.left, width)
        onNodeWithTag(ROW).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun theMenuIndicatorIsTheSmallGlyphUnlessToldOtherwise() = atEveryWidth { width ->
        show(width) {
            Column {
                LettaMenuIndicator(Modifier.testTag(SMALL))
                LettaMenuIndicator(Modifier.testTag(LARGE), style = LettaMenuIndicatorStyle(compact = false))
                LettaMenuIndicator(
                    Modifier.size(LettaSpaceTokens.lg).testTag(SIZED),
                    style = LettaMenuIndicatorStyle(tint = MaterialTheme.colorScheme.primary),
                )
            }
        }
        listOf(SMALL to LettaControlTokens.iconSm, LARGE to LettaControlTokens.icon, SIZED to LettaSpaceTokens.lg).forEach { (tag, size) ->
            val glyph = bounds(tag)
            assertNear(size, glyph.right - glyph.left)
            assertNear(size, glyph.bottom - glyph.top)
        }
    }

    private fun atEveryWidth(assertion: ComposeUiTest.(Dp) -> Unit) {
        for (width in WIDTHS) {
            runDesktopComposeUiTest(width = 1400, height = 900) { assertion(width) }
        }
    }

    private fun ComposeUiTest.show(width: Dp, content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalLettaReducedMotion provides true) {
                MaterialTheme { Box(Modifier.width(width)) { content() } }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.height(tag: String): Dp = bounds(tag).let { it.bottom - it.top }

    private fun assertNear(expected: Dp, actual: Dp) {
        assertTrue(abs(expected.value - actual.value) <= TOLERANCE.value, "expected $expected, actual $actual (tolerance $TOLERANCE)")
    }

    private companion object {
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
        val TOLERANCE = 0.5.dp
        const val PLAIN = "plain"
        const val HEADER = "header"
        const val ROW = "row"
        const val SMALL = "small"
        const val LARGE = "large"
        const val SIZED = "sized"
        val LABEL = "A header whose words run well past the widest frame ".repeat(4)
    }
}
