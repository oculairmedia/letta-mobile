package com.letta.mobile.ui.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.compose.runtime.CompositionLocalProvider
import com.letta.mobile.ui.theme.LocalChatFontScale

@RunWith(RobolectricTestRunner::class)
class LatencyTextUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun latencyText_rendersCorrectText() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalChatFontScale provides 2f) {
                LatencyText(latencyMs = 1500f)
            }
        }

        composeTestRule.onNodeWithText("1.5s").assertExists()
    }
}
