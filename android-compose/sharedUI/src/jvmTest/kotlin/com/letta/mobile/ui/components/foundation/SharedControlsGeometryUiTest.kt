@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import com.letta.mobile.ui.theme.tokens.LettaTextAccents
import com.letta.mobile.ui.theme.tokens.LocalLettaReducedMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shared controls that duplicates were merged onto keep the sizes their callers had, from the
 * 360 dp phone to a 1200 dp window: an inline click target adds no height, a standalone one keeps the
 * touch minimum, every status tone draws in one slot, a chip is its words plus its inset whatever
 * the frame, and every menu row is one height with or without an icon.
 */
class SharedControlsGeometryUiTest {

    @Test
    fun anInlineClickTargetAddsNoHeightAndAStandaloneOneKeepsTheTouchMinimum() = atEveryWidth { width ->
        show(width) {
            Column {
                Box(Modifier.testTag(PLAIN)) { Text("Runs") }
                Box(Modifier.testTag(INLINE)) {
                    Text("Runs", Modifier.lettaClickable(role = Role.Button, target = LettaClickTarget.Inline) {})
                }
                Box(Modifier.testTag(STANDALONE)) { Text("Runs", Modifier.lettaClickable(role = Role.Button) {}) }
            }
        }
        assertNear(height(PLAIN), height(INLINE))
        assertTrue(height(STANDALONE) >= LettaControlTokens.minTouchTarget, "standalone ${height(STANDALONE)} at $width")
        onNodeWithTag(INLINE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun everyStatusToneDrawsInOneSlot() = atEveryWidth { width ->
        show(width) {
            Column {
                StatusTone.entries.forEach { tone ->
                    StatusGlyph(tone, Modifier.testTag("line-${tone.name}"))
                    StatusGlyph(tone, Modifier.testTag("list-${tone.name}"), size = LettaControlTokens.icon)
                }
            }
        }
        StatusTone.entries.forEach { tone ->
            val line = bounds("line-${tone.name}")
            val list = bounds("list-${tone.name}")
            assertNear(LettaControlTokens.iconSm, line.right - line.left)
            assertNear(LettaControlTokens.iconSm, line.bottom - line.top)
            assertNear(LettaControlTokens.icon, list.right - list.left)
            assertNear(LettaControlTokens.icon, list.bottom - list.top)
        }
    }

    @Test
    fun aChipIsItsWordsPlusItsInsetAtEveryWidth() = atEveryWidth { width ->
        show(width) {
            Column {
                Box(Modifier.testTag(PLAIN)) { Text("Archived", style = LettaTextAccents.labelMediumRegular) }
                LettaChipTab(text = "Archived", active = false, onClick = {}, modifier = Modifier.testTag(CHIP))
                LettaChipTab(text = "Archived", active = true, onClick = {}, modifier = Modifier.testTag(ACTIVE))
                LettaChipTab(
                    text = null,
                    active = false,
                    onClick = {},
                    modifier = Modifier.testTag(MARK_ONLY),
                    leading = { LettaColorSwatch(LettaSwatch(Color.Red, size = LettaControlTokens.icon)) },
                )
            }
        }
        assertNear(height(PLAIN) + LettaSpaceTokens.sm * 2, height(CHIP))
        assertNear(height(CHIP), height(ACTIVE))
        assertNear(LettaControlTokens.icon + LettaSpaceTokens.sm * 2, height(MARK_ONLY))
        val chip = bounds(CHIP)
        assertTrue(chip.right - chip.left < width, "the chip wraps its words at $width")
        onNodeWithTag(ACTIVE).assertIsSelected().assert(hasClickAction())
    }

    @Test
    fun everyMenuRowIsOneHeightWithOrWithoutAnIcon() = atEveryWidth { width ->
        show(width) {
            Column {
                Box(Modifier.testTag(ICON_ROW)) { LettaMenuEntry(LettaMenuItem("Duplicate", LettaIcons.Check) {}, onDismiss = {}) }
                Box(Modifier.testTag(BARE_ROW)) { LettaMenuEntry(LettaMenuItem("Duplicate") {}, onDismiss = {}) }
            }
        }
        assertNear(height(ICON_ROW), height(BARE_ROW))
    }

    private fun atEveryWidth(assertion: ComposeUiTest.(Dp) -> Unit) {
        for (width in WIDTHS) {
            runDesktopComposeUiTest(FOUNDATION_WINDOW_WIDTH, FOUNDATION_WINDOW_HEIGHT) { assertion(width) }
        }
    }

    private fun ComposeUiTest.show(width: Dp, content: @Composable () -> Unit) {
        setFoundationContent(width) { CompositionLocalProvider(LocalLettaReducedMotion provides true) { content() } }
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
        const val INLINE = "inline"
        const val STANDALONE = "standalone"
        const val CHIP = "chip"
        const val ACTIVE = "active-chip"
        const val MARK_ONLY = "mark-only-chip"
        const val ICON_ROW = "icon-row"
        const val BARE_ROW = "bare-row"
    }
}
