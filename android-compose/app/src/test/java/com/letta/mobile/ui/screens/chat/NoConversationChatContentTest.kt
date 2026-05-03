package com.letta.mobile.ui.screens.chat

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class NoConversationChatContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun freshClientModeOptimisticPromptIsVisibleWhileConversationIdIsPending() {
        val prompt = "qkct fresh optimistic prompt sentinel"

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    NoConversationChatContent(
                        state = ChatUiState(
                            conversationState = ConversationState.NoConversation,
                            messages = persistentListOf(
                                UiMessage(
                                    id = "client-user-qkct",
                                    role = "user",
                                    content = prompt,
                                    timestamp = "2026-05-03T00:00:00Z",
                                ),
                            ),
                            isStreaming = true,
                            isAgentTyping = true,
                        ),
                        onSendMessage = {},
                        onRerunMessage = {},
                        onLoadOlderMessages = {},
                        onSubmitApproval = { _, _, _, _ -> },
                        onToggleRunCollapsed = {},
                        onToggleReasoningExpanded = {},
                        onOpenLocationPicker = {},
                        modifier = Modifier,
                    )
                }
            }
        }

        composeRule.onNodeWithText(prompt).assertIsDisplayed()
    }
}
