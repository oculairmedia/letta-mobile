package com.letta.mobile.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.ChatMessageList
import com.letta.mobile.feature.chat.state.ChatUiState
import com.letta.mobile.ui.common.GroupPosition
import com.letta.mobile.ui.test.setLettaTestContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class ChatMessageListFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scrollingMessageListClearsFocus() {
        var isTextFieldFocused = false
        val focusRequester = FocusRequester()

        val messages = List(20) { index ->
            UiMessage(
                id = "msg-$index",
                role = if (index % 2 == 0) "user" else "assistant",
                content = "Message content $index",
                timestamp = "2026-04-20T12:00:00Z"
            )
        }
        val renderItems = messages.map {
            ChatRenderItem.Single(it, GroupPosition.None)
        }
        val state = ChatUiState(messages = messages)

        composeRule.setLettaTestContent {
            Column(modifier = Modifier.fillMaxSize()) {
                var text by remember { mutableStateOf("") }
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .onFocusChanged { isTextFieldFocused = it.isFocused }
                        .testTag("text_field")
                )

                ChatMessageList(
                    state = state,
                    renderItems = renderItems,
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
                    onAttachmentImageTap = null,
                    modifier = Modifier.testTag("chat_list")
                )
            }
        }

        // Request focus on the TextField
        composeRule.runOnIdle {
            focusRequester.requestFocus()
        }
        composeRule.waitForIdle()

        // Verify the TextField is focused initially
        assertTrue(isTextFieldFocused)

        // Perform a scroll/swipe gesture on the ChatMessageList
        composeRule.onNodeWithTag("chat_list").performTouchInput {
            swipeUp()
        }
        composeRule.waitForIdle()

        // Verify the TextField lost focus
        assertFalse("TextField should lose focus when ChatMessageList is scrolled", isTextFieldFocused)
    }
}
