package com.letta.mobile.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.letta.mobile.ui.motion.ChatMotionPolicy
import com.letta.mobile.ui.theme.LocalChatFontScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LiveStatusTextUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun liveStatusText_activeState_rendersTextInSemanticsTree() {
        composeTestRule.setContent {
            LiveStatusText(
                text = "Executing tool call...",
                active = true,
            )
        }

        composeTestRule.onNodeWithText("Executing tool call...").assertExists()
    }

    @Test
    fun liveStatusText_terminalState_rendersStaticTextInSemanticsTree() {
        composeTestRule.setContent {
            LiveStatusText(
                text = "Execution finished",
                active = false,
            )
        }

        composeTestRule.onNodeWithText("Execution finished").assertExists()
    }

    @Test
    fun liveStatusText_reducedMotion_rendersTextStatically() {
        composeTestRule.setContent {
            LiveStatusText(
                text = "Processing data...",
                active = true,
                motionPolicy = ChatMotionPolicy.Reduced,
            )
        }

        composeTestRule.onNodeWithText("Processing data...").assertExists()
    }

    @Test
    fun liveStatusText_supportsHighFontScale() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalChatFontScale provides 1.5f) {
                LiveStatusText(
                    text = "High font scale live status text",
                    active = true,
                )
            }
        }

        composeTestRule.onNodeWithText("High font scale live status text").assertExists()
    }
}
