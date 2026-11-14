@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import kotlin.test.Test
import kotlin.test.assertEquals

class LettaPanelTest {
    @Test
    fun eachToneSetsItsOwnContentColour() = runDesktopComposeUiTest {
        val seen = mutableMapOf<LettaPanelTone, Pair<Color, Color>>()
        setFoundationContent {
            LettaPanelTone.entries.forEach { tone ->
                LettaPanel(tone = tone) {
                    seen[tone] = LocalContentColor.current to tone.contentColor()
                    Text(tone.name)
                }
            }
        }
        waitForIdle()
        assertEquals(LettaPanelTone.entries.toSet(), seen.keys)
        seen.forEach { (tone, colours) -> assertEquals(colours.second, colours.first, "$tone content colour") }
    }

    @Test
    fun theErrorPanelUsesTheErrorContainer() = runDesktopComposeUiTest {
        var expected = Color.Unspecified
        var actual = Color.Unspecified
        setFoundationContent {
            expected = MaterialTheme.colorScheme.errorContainer
            actual = LettaPanelTone.Error.containerColor()
        }
        waitForIdle()
        assertEquals(expected, actual)
    }

    @Test
    fun aNoticeFillsItsWidthAndOffersItsActions() {
        FOUNDATION_WIDTHS.forEach { width ->
            runDesktopComposeUiTest {
                var acted = 0
                var dismissed = 0
                setFoundationContent(width) {
                    LettaInlineNotice(
                        message = "Read only while offline",
                        modifier = Modifier.fillMaxWidth().testTag(NOTICE),
                        tone = LettaPanelTone.Warning,
                        title = "Offline",
                        action = LettaNoticeAction("Retry", { acted++ }, tag = ACTION),
                        dismiss = LettaNoticeAction("Dismiss", { dismissed++ }, tag = DISMISS),
                    )
                }
                onNodeWithTag(NOTICE).assertWidthIsEqualTo(width)
                onNodeWithText("Read only while offline").assertExists()
                onNodeWithTag(ACTION).performClick()
                onNodeWithTag(DISMISS).performClick()
                assertEquals(1 to 1, acted to dismissed)
            }
        }
    }

    private companion object {
        const val NOTICE = "notice"
        const val ACTION = "action"
        const val DISMISS = "dismiss"
    }
}
