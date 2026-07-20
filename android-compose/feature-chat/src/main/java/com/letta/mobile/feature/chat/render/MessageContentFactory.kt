package com.letta.mobile.feature.chat.render

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage

internal val LocalStreamingRevealHapticPulse = compositionLocalOf<() -> Unit> { {} }

internal interface MessageContentRenderer {
    fun canRender(message: UiMessage): Boolean

    @Composable
    fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)? = null,
        onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)? = null,
        isStreaming: Boolean = false,
    )
}

internal val defaultRenderers = listOf(ToolCallRenderer, SubagentNotificationRenderer, TextMessageRenderer)

internal fun resolveRenderer(message: UiMessage): MessageContentRenderer {
    return defaultRenderers.firstOrNull { it.canRender(message) } ?: TextMessageRenderer
}
