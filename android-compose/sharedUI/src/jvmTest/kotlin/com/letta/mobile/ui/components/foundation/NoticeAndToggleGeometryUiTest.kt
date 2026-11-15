@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The switch row and the notice after they took the parts their last hand-built copies needed, from
 * the 360 dp phone to a 1200 dp window: a row whose helper may wrap grows past the one-line row on a
 * phone and never falls under the touch minimum, and an elevated notice with an icon dismiss is the
 * same height as the plain notice it replaced, its close mark the shared icon button's size.
 */
class NoticeAndToggleGeometryUiTest {

    @Test
    fun aWrappingHelperGrowsTheRowAndNeverUnderTheTouchMinimum() = atEveryWidth { width ->
        setFoundationContent(width) {
            Column {
                LettaToggleRow(
                    LettaRowLabel(TITLE, LONG_HELPER),
                    checked = true,
                    onCheckedChange = {},
                    modifier = Modifier.testTag(ONE_LINE),
                )
                LettaToggleRow(
                    LettaRowLabel(TITLE, LONG_HELPER, supportingMaxLines = Int.MAX_VALUE),
                    checked = true,
                    onCheckedChange = {},
                    modifier = Modifier.testTag(WRAPPING),
                )
            }
        }
        val oneLine = height(ONE_LINE)
        val wrapping = height(WRAPPING)
        assertTrue(oneLine >= LettaControlTokens.minTouchTarget, "one-line row $oneLine at $width")
        assertTrue(wrapping >= oneLine, "wrapping row $wrapping under one-line $oneLine at $width")
        if (width == WIDTHS.first()) assertTrue(wrapping > oneLine, "the helper wraps on a phone: $wrapping vs $oneLine")
    }

    @Test
    fun anElevatedNoticeWithAnIconDismissKeepsThePlainNoticesHeight() = atEveryWidth { width ->
        setFoundationContent(width) {
            Column {
                LettaInlineNotice(
                    LettaNotice(MESSAGE, tone = LettaPanelTone.Info),
                    Modifier.testTag(PLAIN),
                    dismiss = LettaNoticeAction("Dismiss", onClick = {}, tag = "plain-dismiss"),
                )
                LettaInlineNotice(
                    LettaNotice(MESSAGE, tone = LettaPanelTone.Info, elevated = true),
                    Modifier.testTag(ELEVATED),
                    dismiss = LettaNoticeAction("Dismiss", onClick = {}, tag = CLOSE, icon = LettaIcons.Close),
                )
            }
        }
        assertTrue(height(ELEVATED) <= height(PLAIN) + TOLERANCE, "elevated ${height(ELEVATED)} vs plain ${height(PLAIN)} at $width")
        val close = onNodeWithTag(CLOSE).getUnclippedBoundsInRoot()
        assertNear(LettaControlTokens.iconButton, close.right - close.left)
        val notice = onNodeWithTag(ELEVATED).getUnclippedBoundsInRoot()
        assertTrue(close.right <= notice.right, "close $close inside notice $notice at $width")
    }

    private fun atEveryWidth(assertion: ComposeUiTest.(Dp) -> Unit) {
        for (width in WIDTHS) {
            runDesktopComposeUiTest(FOUNDATION_WINDOW_WIDTH, FOUNDATION_WINDOW_HEIGHT) { assertion(width) }
        }
    }

    private fun ComposeUiTest.height(tag: String): Dp = onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun assertNear(expected: Dp, actual: Dp) {
        assertTrue(abs(expected.value - actual.value) <= TOLERANCE.value, "expected $expected, actual $actual")
    }

    private companion object {
        val WIDTHS = listOf(360.dp, 700.dp, 760.dp, 1200.dp)
        val TOLERANCE = 0.5.dp
        const val TITLE = "Let the paired device change settings"
        val LONG_HELPER = "It can change this device's agents, models and backends while the pairing lasts. ".repeat(2)
        const val MESSAGE = "Reconnected to the server"
        const val ONE_LINE = "one-line"
        const val WRAPPING = "wrapping"
        const val PLAIN = "plain-notice"
        const val ELEVATED = "elevated-notice"
        const val CLOSE = "close"
    }
}
