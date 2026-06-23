package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertDoesNotExist
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.screen.ToolCallCard
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ToolCallStatusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun testSuccessShowsNoStatusChrome() {
        composeRule.setContent {
            LettaTheme(appTheme = com.letta.mobile.data.model.AppTheme.LIGHT, themePreset = com.letta.mobile.data.model.ThemePreset.DEFAULT, dynamicColor = false) {
                LettaChatTheme {
                    ToolCallCard(
                        toolCall = UiToolCall(
                            name = "test_tool",
                            arguments = "{}",
                            result = "Success result",
                            status = "success",
                            toolCallId = "call-1"
                        )
                    )
                }
            }
        }

        // Verify success indicator doesn't explicitly mention "Status" in summary line
        composeRule.onNodeWithText("Status:").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Success").assertIsDisplayed()
    }

    @Test
    fun testErrorShowsRedRuleIndicator() {
        composeRule.setContent {
            LettaTheme(appTheme = com.letta.mobile.data.model.AppTheme.LIGHT, themePreset = com.letta.mobile.data.model.ThemePreset.DEFAULT, dynamicColor = false) {
                LettaChatTheme {
                    ToolCallCard(
                        toolCall = UiToolCall(
                            name = "test_tool",
                            arguments = "{}",
                            result = "Error result",
                            status = "error",
                            toolCallId = "call-1"
                        )
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Error").assertIsDisplayed()
    }

    @Test
    fun testWarningShowsAmberIndicator() {
        composeRule.setContent {
            LettaTheme(appTheme = com.letta.mobile.data.model.AppTheme.LIGHT, themePreset = com.letta.mobile.data.model.ThemePreset.DEFAULT, dynamicColor = false) {
                LettaChatTheme {
                    ToolCallCard(
                        toolCall = UiToolCall(
                            name = "test_tool",
                            arguments = "{}",
                            result = "Warning result",
                            status = "warning",
                            toolCallId = "call-1"
                        )
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Warning").assertIsDisplayed()
    }

    @Test
    fun testRunningShowsTransientIndicator() {
        composeRule.setContent {
            LettaTheme(appTheme = com.letta.mobile.data.model.AppTheme.LIGHT, themePreset = com.letta.mobile.data.model.ThemePreset.DEFAULT, dynamicColor = false) {
                LettaChatTheme {
                    ToolCallCard(
                        toolCall = UiToolCall(
                            name = "test_tool",
                            arguments = "{}",
                            result = null, // Running
                            status = null,
                            toolCallId = "call-1"
                        )
                    )
                }
            }
        }

        // When running, we show the transient indicator (Refresh icon with "Running" content description)
        composeRule.onNodeWithContentDescription("Running").assertIsDisplayed()
    }
}
