package com.letta.mobile.feature.chat

import com.letta.mobile.ui.chat.render.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.letta.mobile.feature.chat.screen.ChatContentAppearance
import com.letta.mobile.feature.chat.screen.ChatContentCallbacks
import com.letta.mobile.feature.chat.screen.NoConversationChatContent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class NoConversationChatContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun testCallbacks(
        onDismissA2uiSurface: (String) -> Unit = {},
    ) = ChatContentCallbacks(
        onSendMessage = {},
        onRerunMessage = {},
        onLoadOlderMessages = {},
        onSubmitApproval = { _, _, _, _ -> },
        onToggleRunCollapsed = {},
        onToggleReasoningExpanded = {},
        onDismissA2uiSurface = onDismissA2uiSurface,
        onAttachmentImageTap = null,
    )

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
                                com.letta.mobile.data.model.UiMessage(
                                    id = "client-user-qkct",
                                    role = "user",
                                    content = prompt,
                                    timestamp = "2026-05-03T00:00:00Z",
                                ),
                            ),
                            isStreaming = true,
                            isAgentTyping = true,
                        ),
                        callbacks = testCallbacks(),
                        appearance = ChatContentAppearance(),
                        modifier = Modifier,
                    )
                }
            }
        }

        composeRule.onNodeWithText(prompt).assertIsDisplayed()
    }

    @Test
    fun a2uiSurfaceIsRemovedWhenStateMapDeletesIt() {
        val surface = confirmationSurfaceManager().surface(SurfaceId)!!
        var surfaces: ImmutableMap<String, A2uiSurfaceState> by mutableStateOf(
            persistentMapOf(surface.surfaceId to surface),
        )

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
                            messages = persistentListOf(),
                            isStreaming = false,
                            isAgentTyping = false,
                            a2uiSurfaces = surfaces,
                        ),
                        callbacks = testCallbacks(),
                        appearance = ChatContentAppearance(),
                        modifier = Modifier,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Review tool call").assertIsDisplayed()

        composeRule.runOnIdle {
            surfaces = persistentMapOf()
        }

        composeRule.onAllNodesWithText("Review tool call").assertCountEquals(0)
    }

    @Test
    fun longPressA2uiSurfaceCanDismissItFromLiveState() {
        val surface = confirmationSurfaceManager().surface(SurfaceId)!!
        var dismissedSurfaceId: String? = null
        var surfaces: ImmutableMap<String, A2uiSurfaceState> by mutableStateOf(
            persistentMapOf(surface.surfaceId to surface),
        )

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
                            messages = persistentListOf(),
                            isStreaming = false,
                            isAgentTyping = false,
                            a2uiSurfaces = surfaces,
                        ),
                        callbacks = testCallbacks(
                            onDismissA2uiSurface = { surfaceId ->
                                dismissedSurfaceId = surfaceId
                                surfaces = persistentMapOf()
                            },
                        ),
                        appearance = ChatContentAppearance(),
                        modifier = Modifier,
                    )
                }
            }
        }

        composeRule.onNodeWithText("Review tool call").performTouchInput {
            down(center)
            advanceEventTime(1_200)
            up()
        }
        composeRule.onNodeWithText("Delete").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            assertEquals(SurfaceId, dismissedSurfaceId)
        }
        composeRule.onAllNodesWithText("Review tool call").assertCountEquals(0)
    }
}
