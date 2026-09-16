package com.letta.mobile.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    /**
     * The reasoning body enters behind an AnimatedVisibility, so the node exists before it is
     * displayed. Settle that motion on the test clock rather than asserting on the frame that
     * happens to follow setContent, which passes or fails with machine load.
     */
    /**
     * Every caller asserts the reasoning body is displayed next, so settle on that condition
     * rather than a fixed 1s clock advance: under a loaded full-suite run the enter transition
     * could still be at zero height on the frame after the advance and the assert would flake.
     */
    private fun settleReasoningEnter() = awaitReasoningContent(present = true)

    /**
     * Waits for the reasoning body to finish entering or leaving instead of advancing the clock by
     * a fixed amount and asserting on whatever frame follows: the enter/exit runs behind an
     * AnimatedVisibility, so on a loaded machine one 1s advance plus waitForIdle could land while
     * the node was still absent (collapseStateSurvivesContentUpdatesAndStreamingCompletion failed
     * that way in CI). waitUntil drives the same test clock, so this stays deterministic.
     */
    private fun awaitReasoningContent(present: Boolean) {
        composeRule.waitUntil(REASONING_MOTION_TIMEOUT_MS) {
            val mounted = composeRule.onAllNodesWithTag(ChatReasoningTestTags.Content).fetchSemanticsNodes().isNotEmpty()
            when {
                !present -> !mounted
                !mounted -> false
                // Mounted is not yet visible: the expand transition starts at zero height, so wait
                // for the frame where the body actually occupies the screen before asserting on it.
                else -> runCatching {
                    composeRule.onNodeWithTag(ChatReasoningTestTags.Content).assertIsDisplayed()
                }.isSuccess
            }
        }
        composeRule.waitForIdle()
    }

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

        composeRule.onAllNodesWithText("Thinking…").assertCountEquals(2)
        composeRule.onAllNodesWithTag(ChatReasoningTestTags.LiveStatus).assertCountEquals(1)
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

        settleReasoningEnter()
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
        settleReasoningEnter()
        composeRule.onNodeWithTag(ChatReasoningTestTags.Content).assertIsDisplayed()

        // Non-prefix replacement
        composeRule.runOnIdle {
            messageState.value = messageState.value.copy(
                content = "Resetting trace",
            )
        }

        composeRule.waitForIdle()
        settleReasoningEnter()
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

        awaitReasoningContent(present = true)
        composeRule.onNodeWithTag(ChatReasoningTestTags.Content).assertIsDisplayed()
    }

    @Test
    fun collapseStateSurvivesContentUpdatesAndStreamingCompletion() {
        val messageState = mutableStateOf(
            UiMessage(
                id = "reasoning-lifecycle",
                role = "assistant",
                content = "Initial reasoning",
                timestamp = "2026-07-26T12:00:00Z",
                isReasoning = true,
            ),
        )
        val streamingState = mutableStateOf(false)
        val collapsedState = mutableStateOf(true)

        composeRule.setContent {
            LettaTheme(AppTheme.LIGHT, ThemePreset.DEFAULT, false) {
                LettaChatTheme {
                    MessageReasoning(
                        message = messageState.value,
                        isStreaming = streamingState.value,
                        collapsed = collapsedState.value,
                        onToggleCollapsed = { collapsedState.value = !collapsedState.value },
                    )
                }
            }
        }

        val header = composeRule.onNodeWithTag(ChatReasoningTestTags.Header)
        val content = composeRule.onNodeWithTag(ChatReasoningTestTags.Content)
        header.assert(reasoningState("Reasoning collapsed", actionLabel = "Expand reasoning"))
        content.assertDoesNotExist()

        header.performClick()
        awaitReasoningContent(present = true)
        header.assert(reasoningState("Reasoning expanded", actionLabel = "Collapse reasoning"))
        content.assertIsDisplayed()

        composeRule.runOnIdle {
            messageState.value = messageState.value.copy(content = "Initial reasoning with more tokens")
        }
        content.assertIsDisplayed()

        header.performClick()
        awaitReasoningContent(present = false)
        content.assertDoesNotExist()

        composeRule.runOnIdle {
            streamingState.value = true
            messageState.value = messageState.value.copy(content = "Streaming reasoning")
        }
        awaitReasoningContent(present = true)
        header.assert(reasoningState("Reasoning in progress", actionLabel = null))
        content.assertIsDisplayed()

        composeRule.runOnIdle {
            messageState.value = messageState.value.copy(content = "Streaming reasoning completed", isError = true)
            streamingState.value = false
        }
        awaitReasoningContent(present = false)
        header.assert(reasoningState("Reasoning collapsed", actionLabel = "Expand reasoning"))
        content.assertDoesNotExist()
    }

    private fun reasoningState(state: String, actionLabel: String?): SemanticsMatcher {
        val stateMatcher = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)
        val actionMatcher = if (actionLabel == null) {
            SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
        } else {
            SemanticsMatcher("onClick label=$actionLabel") { node ->
                runCatching { node.config[SemanticsActions.OnClick] }.getOrNull()?.label == actionLabel
            }
        }
        return stateMatcher.and(actionMatcher)
    }

    private companion object {
        /** Generous: it bounds a hang, it is not the expected duration (the clock is virtual). */
        const val REASONING_MOTION_TIMEOUT_MS = 10_000L
    }
}
