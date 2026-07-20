package com.letta.mobile.feature.chat.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.feature.chat.screen.MessageToolCalls
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.chat.render.RenderDiagnostics
import kotlinx.collections.immutable.toImmutableList

internal object ToolCallRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage) =
        !message.toolCalls.isNullOrEmpty()

    @Composable
    override fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
        onAttachmentImageTap: ((List<UiImageAttachment>, Int) -> Unit)?,
        isStreaming: Boolean,
    ) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (message.content.isNotBlank()) {
                renderToolCallMessageText(
                    message = message,
                    textColor = textColor,
                    isStreaming = isStreaming,
                )
            }
            message.toolCalls?.takeIf { it.isNotEmpty() }?.let { toolCalls ->
                // Wrap at the call-site so MessageToolCalls receives a stable
                // ImmutableList param (o7ob.2.6). UiMessage still uses raw List
                // to avoid rippling the migration through MessageMapper.
                val stableToolCalls = remember(toolCalls) {
                    toolCalls.toImmutableList()
                }
                MessageToolCalls(
                    toolCalls = stableToolCalls,
                    messageId = message.id,
                    animateEntrance = shouldAnimateToolCallEntrance(isStreaming),
                    approvalRequest = message.approvalRequest,
                    onAttachmentImageTap = onAttachmentImageTap,
                )
            }
        }
    }
}

@Composable
private fun renderToolCallMessageText(
    message: UiMessage,
    textColor: Color,
    isStreaming: Boolean,
) {
    if (message.role == "assistant") {
        AssistantResponseText(
            AssistantResponseTextProps(
                messageId = message.id,
                text = message.content,
                textColor = textColor,
                isStreaming = isStreaming,
            ),
        )
        return
    }

    androidx.compose.runtime.SideEffect {
        RenderDiagnostics.onDisplayedText(
            conversationId = "",
            site = "final",
            serverId = message.id,
            text = message.content,
        )
    }
    MarkdownText(text = message.content, textColor = textColor)
}

internal fun shouldAnimateToolCallEntrance(isStreaming: Boolean): Boolean = isStreaming
