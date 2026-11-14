@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import kotlin.test.Test
import kotlin.test.assertEquals

class LettaIconTest {
    @Test
    fun anIconDrawsAtTheScalesGlyphSizeByDefault() = runDesktopComposeUiTest(FOUNDATION_WINDOW_WIDTH, FOUNDATION_WINDOW_HEIGHT) {
        setFoundationContent {
            Row {
                LettaIcon(LettaIcons.Search, contentDescription = null, modifier = Modifier.testTag("default"))
                LettaIcon(LettaIcons.Search, contentDescription = null, modifier = Modifier.testTag("small"), size = LettaControlTokens.iconSm)
            }
        }
        onNodeWithTag("default").assertWidthIsEqualTo(LettaControlTokens.icon).assertHeightIsEqualTo(LettaControlTokens.icon)
        onNodeWithTag("small").assertWidthIsEqualTo(LettaControlTokens.iconSm)
    }

    @Test
    fun anIconButtonIsAButtonDrawnCompactWithAFullTouchTarget() = runDesktopComposeUiTest(FOUNDATION_WINDOW_WIDTH, FOUNDATION_WINDOW_HEIGHT) {
        var clicks = 0
        setFoundationContent {
            LettaIconButton(LettaIcons.Close, contentDescription = "Close", onClick = { clicks++ })
        }
        onNodeWithContentDescription("Close", useUnmergedTree = false)
            .assert(hasRole(Role.Button))
            .assertWidthIsEqualTo(LettaControlTokens.iconButton)
            .assertHeightIsEqualTo(LettaControlTokens.iconButton)
            .assertTouchWidthIsEqualTo(LettaControlTokens.minTouchTarget)
            .assertTouchHeightIsEqualTo(LettaControlTokens.minTouchTarget)
            .performClick()
        assertEquals(1, clicks)
    }
}
