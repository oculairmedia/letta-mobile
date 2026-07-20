package com.letta.mobile.feature.chat.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.SubagentNotificationCard

internal object SubagentNotificationRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage): Boolean =
        message.subagentNotification != null

    @Composable
    override fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
        onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)?,
        isStreaming: Boolean,
    ) {
        SubagentNotificationCard(
            notification = message.subagentNotification ?: return,
            modifier = modifier,
        )
    }
}
