package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.ui.components.THINKING_TEXT_TOKEN_TEST_TAG
import com.letta.mobile.ui.components.ThinkingTextToken
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class ThinkingTextTokenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `hidden when not visible and no delay message`() {
        composeRule.setContent {
            ThemedToken(visible = false, delayMessage = null)
        }
        composeRule.onAllNodesWithTag(THINKING_TEXT_TOKEN_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun `renders thinking text when visible`() {
        composeRule.setContent {
            ThemedToken(visible = true, delayMessage = null)
        }
        composeRule.onNodeWithTag(THINKING_TEXT_TOKEN_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Thinking…").assertIsDisplayed()
    }

    @Test
    fun `renders delay subtitle when timeout fired even if no longer typing`() {
        composeRule.setContent {
            ThemedToken(
                visible = false,
                delayMessage = "Response delayed — check your connection",
            )
        }
        composeRule.onNodeWithTag(THINKING_TEXT_TOKEN_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Response delayed — check your connection").assertIsDisplayed()
    }

    @Test
    fun `delay subtitle takes precedence over base text when both visible`() {
        composeRule.setContent {
            ThemedToken(
                visible = true,
                delayMessage = "Response delayed — check your connection",
            )
        }
        composeRule.onNodeWithText("Response delayed — check your connection").assertIsDisplayed()
    }

    @Test
    fun `reducedMotion still renders the text`() {
        composeRule.setContent {
            ThemedToken(visible = true, delayMessage = null, reducedMotion = true)
        }
        composeRule.onNodeWithText("Thinking…").assertIsDisplayed()
    }
}

@androidx.compose.runtime.Composable
private fun ThemedToken(
    visible: Boolean,
    delayMessage: String?,
    reducedMotion: Boolean = false,
) {
    LettaTheme(
        appTheme = AppTheme.LIGHT,
        themePreset = ThemePreset.DEFAULT,
        dynamicColor = false,
    ) {
        LettaChatTheme {
            ThinkingTextToken(
                visible = visible,
                delayMessage = delayMessage,
                reducedMotion = reducedMotion,
            )
        }
    }
}
