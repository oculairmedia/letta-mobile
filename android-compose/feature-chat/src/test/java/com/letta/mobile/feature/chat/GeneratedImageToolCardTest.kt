package com.letta.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.coordination.ChatRenderItem
import com.letta.mobile.feature.chat.render.ChatUiState
import com.letta.mobile.feature.chat.render.ConversationState
import com.letta.mobile.feature.chat.screen.ChatImageViewer
import com.letta.mobile.feature.chat.screen.ChatImageViewerScaleKey
import com.letta.mobile.feature.chat.screen.ChatMessageList
import com.letta.mobile.feature.chat.screen.CompactToolCallGroupCard
import com.letta.mobile.feature.chat.screen.ToolCallCard
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.theme.LettaTheme
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class GeneratedImageToolCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun generatedImageToolCardOpensSharedViewerWithActions() {
        composeRule.setContent {
            var viewerState by remember { mutableStateOf<Pair<List<UiImageAttachment>, Int>?>(null) }
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    ToolCallCard(
                        toolCall = generatedImageToolCall(),
                        onAttachmentImageTap = { attachments, index -> viewerState = attachments to index },
                    )
                    viewerState?.let { (attachments, index) ->
                        ChatImageViewer(
                            attachments = attachments.toImmutableList(),
                            initialPage = index,
                            onDismiss = { viewerState = null },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Generated image").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open image").performClick()

        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share image").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save image").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun streamingMessageListImageTapOpensViewer() {
        val attachment = UiImageAttachment(
            base64 = OnePixelPngBase64,
            mediaType = "image/png",
        )
        val message = UiMessage(
            id = "streaming-image",
            role = "assistant",
            content = "Image ready",
            timestamp = "2026-06-06T00:00:00Z",
            attachments = listOf(attachment),
        )

        composeRule.setContent {
            var viewerState by remember { mutableStateOf<Pair<List<UiImageAttachment>, Int>?>(null) }
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    ChatMessageList(
                        state = ChatUiState(
                            conversationState = ConversationState.Ready("conversation"),
                            messages = listOf(message).toImmutableList(),
                            isLoadingMessages = false,
                            isStreaming = true,
                        ),
                        renderItems = listOf(ChatRenderItem.Single(message, GroupPosition.None)),
                        chatMode = "interactive",
                        scrollToMessageId = null,
                        activeFontScale = 1f,
                        onActiveFontScaleChange = {},
                        onFontScaleChange = {},
                        onLoadOlderMessages = {},
                        onSendMessage = {},
                        onRerunMessage = {},
                        onSubmitApproval = { _, _, _, _ -> },
                        onToggleRunCollapsed = {},
                        onToggleReasoningExpanded = {},
                        onAttachmentImageTap = { attachments, index -> viewerState = attachments to index },
                        modifier = Modifier.fillMaxSize(),
                    )
                    viewerState?.let { (attachments, index) ->
                        ChatImageViewer(
                            attachments = attachments.toImmutableList(),
                            initialPage = index,
                            onDismiss = { viewerState = null },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithContentDescription("Open image").performClick()

        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()
    }

    @Test
    fun openViewerReceivesGesturesAboveBackgroundOverlays() {
        composeRule.setContent {
            var backgroundClicks by remember { mutableStateOf(0) }
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Red)
                            .clickable { backgroundClicks += 1 },
                    )
                    ChatImageViewer(
                        attachments = listOf(
                            UiImageAttachment(
                                base64 = OnePixelPngBase64,
                                mediaType = "image/png",
                            ),
                        ).toImmutableList(),
                        initialPage = 0,
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Fullscreen chat image")
            .performTouchInput {
                down(center)
                up()
                advanceEventTime(100)
                down(center)
                up()
            }

        composeRule.onNodeWithContentDescription("Fullscreen chat image")
            .assert(SemanticsMatcher.expectValue(ChatImageViewerScaleKey, 2.5f))
    }

    @Test
    fun groupedGeneratedImageToolCardOpensSharedViewerWhenExpanded() {
        composeRule.setContent {
            var viewerState by remember { mutableStateOf<Pair<List<UiImageAttachment>, Int>?>(null) }
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    CompactToolCallGroupCard(
                        toolCalls = listOf(
                            UiToolCall(
                                name = "Bash",
                                arguments = "{}",
                                result = "ok",
                                status = "success",
                            ),
                            generatedImageToolCall(),
                        ),
                        pendingApprovalToolCallIds = emptySet(),
                        onAttachmentImageTap = { attachments, index -> viewerState = attachments to index },
                    )
                    viewerState?.let { (attachments, index) ->
                        ChatImageViewer(
                            attachments = attachments.toImmutableList(),
                            initialPage = index,
                            onDismiss = { viewerState = null },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("generate_image", substring = true).performClick()
        composeRule.onNodeWithContentDescription("Open image").performClick()

        composeRule.onNodeWithText("1 / 1").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share image").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Save image").assertIsDisplayed()
    }

    private fun generatedImageToolCall(): UiToolCall = UiToolCall(
        name = "generate_image",
        arguments = """{"prompt":"A tiny blue square"}""",
        result = "ok",
        status = "success",
        generatedImageAttachments = listOf(
            UiImageAttachment(
                base64 = OnePixelPngBase64,
                mediaType = "image/png",
            ),
        ),
        toolCallId = "image-call",
    )
}

private const val OnePixelPngBase64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
