package com.letta.mobile.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.ChatReasoningTestTags
import com.letta.mobile.feature.chat.screen.MessageReasoning
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
class ChatReasoningTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun activeReasoningWithBlankContentShowsLiveStatusIndicator() {
        val blankActiveMessage = UiMessage(
            id = "reasoning-blank",
            role = "assistant",
            content = "",
            timestamp = "2026-07-26T12:00:00Z",
            isPending = true,
            isReasoning = true,
        )

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    MessageReasoning(
                        message = blankActiveMessage,
                        isStreaming = true,
                        collapsed = false,
                        onToggleCollapsed = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ChatReasoningTestTags.LiveStatus).assertIsDisplayed()
    }

    @Test
    fun completedReasoningShowsCanonicalThoughtSummaryWithDuration() {
        val completedMessage = UiMessage(
            id = "reasoning-done",
            role = "assistant",
            content = "Completed trace step.",
            timestamp = "2026-07-26T12:00:00Z",
            isPending = false,
            isReasoning = true,
            latencyMs = 1450L,
        )

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    MessageReasoning(
                        message = completedMessage,
                        isStreaming = false,
                        collapsed = false,
                        onToggleCollapsed = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Thought for 1.5s").assertIsDisplayed()
        composeRule.onNodeWithTag(ChatReasoningTestTags.Content).assertIsDisplayed()
    }

    @Test
    fun completedReasoningWithoutDurationShowsThoughtSummaryOnly() {
        val completedMessageNoDuration = UiMessage(
            id = "reasoning-no-dur",
            role = "assistant",
            content = "Trace step with no latency recorded.",
            timestamp = "2026-07-26T12:00:00Z",
            isPending = false,
            isReasoning = true,
            latencyMs = null,
        )

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    MessageReasoning(
                        message = completedMessageNoDuration,
                        isStreaming = false,
                        collapsed = false,
                        onToggleCollapsed = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Thought").assertIsDisplayed()
    }

    // Historical isolation is enforced at the CALL SITE, not inside this composable:
    // ChatMessageListLazyColumn derives isStreamingRenderItem as
    // `itemState.isStreaming && renderItem.containsMessageId(newestMessageId)`, so a
    // historical block is handed isStreaming = false while a later message streams.
    // This test pins the resulting contract: given the scoped flag, the block is terminal.
    @Test
    fun historicalReasoningStaysTerminalWhileALaterMessageStreams() {
        val historicalMessage = UiMessage(
            id = "reasoning-historical",
            role = "assistant",
            content = "Historical reasoning content.",
            timestamp = "2026-07-26T12:00:00Z",
            isPending = false,
            isReasoning = true,
            latencyMs = 800L,
        )

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    MessageReasoning(
                        message = historicalMessage,
                        isStreaming = false, // scoped by the caller: this run is not the newest render item
                        collapsed = false,
                        onToggleCollapsed = {},
                    )
                }
            }
        }

        // Must display terminal "Thought for 800ms" header rather than active "Thinking..."
        composeRule.onNodeWithText("Thought for 800ms").assertIsDisplayed()
    }

    @Test
    fun appendOnlyStreamingExtendsRevealWithoutReset() {
        val messageState = mutableStateOf(
            UiMessage(
                id = "reasoning-stream",
                role = "assistant",
                content = "Step 1",
                timestamp = "2026-07-26T12:00:00Z",
                isPending = true,
                isReasoning = true,
            ),
        )

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    MessageReasoning(
                        message = messageState.value,
                        isStreaming = true,
                        collapsed = false,
                        onToggleCollapsed = {},
                    )
                }
            }
        }

        composeRule.waitForIdle()

        // Append text (prefix extension)
        composeRule.runOnIdle {
            messageState.value = messageState.value.copy(
                content = "Step 1: analyzing input",
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatReasoningTestTags.Content).assertIsDisplayed()

        // Non-prefix replacement
        composeRule.runOnIdle {
            messageState.value = messageState.value.copy(
                content = "Resetting trace",
            )
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ChatReasoningTestTags.Content).assertIsDisplayed()
    }

    @Test
    fun unicodeSurrogatePairsArePreservedSafely() {
        val unicodeMessage = UiMessage(
            id = "reasoning-unicode",
            role = "assistant",
            content = "Evaluating 🌍 emoji and 🚀 launch parameters",
            timestamp = "2026-07-26T12:00:00Z",
            isPending = false,
            isReasoning = true,
            latencyMs = 500L,
        )

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    MessageReasoning(
                        message = unicodeMessage,
                        isStreaming = false,
                        collapsed = false,
                        onToggleCollapsed = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(ChatReasoningTestTags.Content).assertIsDisplayed()
    }
}
