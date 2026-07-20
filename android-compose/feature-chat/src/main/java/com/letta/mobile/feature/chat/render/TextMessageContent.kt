package com.letta.mobile.feature.chat.render

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.theme.scaledBy

internal object TextMessageRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage) =
        message.role == "user" || (message.role == "assistant" && message.toolCalls.isNullOrEmpty())

    @Composable
    override fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
        onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)?,
        isStreaming: Boolean,
    ) {
        if (message.role == "user") {
            Text(
                text = message.content,
                style = MaterialTheme.chatTypography.messageBody.scaledBy(LocalChatFontScale.current),
                color = textColor,
                modifier = modifier,
            )
        } else {
            AssistantResponseText(
                AssistantResponseTextProps(
                    messageId = message.id,
                    text = message.content,
                    textColor = textColor,
                    modifier = modifier,
                    isStreaming = isStreaming,
                ),
            )
        }
    }
}
