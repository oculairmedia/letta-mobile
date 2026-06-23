package com.letta.mobile.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
                delayMessage = "Still working — long tool calls can take a few minutes",
            )
        }
        composeRule.onNodeWithTag(THINKING_TEXT_TOKEN_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Still working — long tool calls can take a few minutes").assertIsDisplayed()
    }

    @Test
    fun `delay subtitle takes precedence over base text when both visible`() {
        composeRule.setContent {
            ThemedToken(
                visible = true,
                delayMessage = "Still working — long tool calls can take a few minutes",
            )
        }
        composeRule.onNodeWithText("Still working — long tool calls can take a few minutes").assertIsDisplayed()
    }

    @Test
    fun `keeps token mounted while reserved streaming slot is active`() {
        var visible by mutableStateOf(true)
        var reserveSpace by mutableStateOf(true)

        composeRule.setContent {
            ThemedToken(
                visible = visible,
                delayMessage = null,
                reducedMotion = true,
                reserveSpace = reserveSpace,
            )
        }

        composeRule.onNodeWithTag(THINKING_TEXT_TOKEN_TEST_TAG).assertIsDisplayed()

        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(THINKING_TEXT_TOKEN_TEST_TAG).assertIsDisplayed()

        composeRule.runOnIdle { visible = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(THINKING_TEXT_TOKEN_TEST_TAG).assertIsDisplayed()

        composeRule.runOnIdle {
            visible = false
            reserveSpace = false
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithTag(THINKING_TEXT_TOKEN_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun `reducedMotion still renders the text`() {
        composeRule.setContent {
            ThemedToken(visible = true, delayMessage = null, reducedMotion = true)
        }
        composeRule.onNodeWithText("Thinking…").assertIsDisplayed()
    }

    @Test
    fun `nested visibility block keeps text mounted when reserveSpace is true`() {
        composeRule.setContent {
            ThemedToken(
                visible = false,
                delayMessage = null,
                reducedMotion = true,
                reserveSpace = true,
            )
        }

        // This is a test-only guard to ensure future nested visibility gates fail tests
        // if they incorrectly dismount the thinking indicator while streaming.
        composeRule.onNodeWithTag(THINKING_TEXT_TOKEN_TEST_TAG).assertIsDisplayed()
    }
}

@androidx.compose.runtime.Composable
private fun ThemedToken(
    visible: Boolean,
    delayMessage: String?,
    reducedMotion: Boolean = false,
    reserveSpace: Boolean = visible || !delayMessage.isNullOrBlank(),
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
                reserveSpace = reserveSpace,
            )
        }
    }
}
