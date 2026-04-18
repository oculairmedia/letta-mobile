package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.theme.scaledBy
import kotlinx.collections.immutable.toImmutableList

object GeneratedUiRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage): Boolean = message.generatedUi != null

    @Composable
    override fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
    ) {
        val generatedUi = message.generatedUi ?: return
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (message.content.isNotBlank()) {
                MarkdownText(text = message.content, textColor = textColor)
            }

            val renderer = GeneratedUiRegistry.resolve(generatedUi.name)
            if (renderer != null) {
                renderer.Render(
                    component = generatedUi,
                    onGeneratedUiMessage = onGeneratedUiMessage,
                )
            } else {
                GeneratedUiFallbackCard(component = generatedUi)
            }
        }
    }
}

interface MessageContentRenderer {
    fun canRender(message: UiMessage): Boolean

    @Composable
    fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)? = null,
    )
}

object TextMessageRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage) =
        message.role == "user" || (message.role == "assistant" && message.toolCalls.isNullOrEmpty())

    @Composable
    override fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
    ) {
        if (message.role == "user") {
            Text(
                text = message.content,
                style = MaterialTheme.chatTypography.messageBody,
                color = textColor,
                modifier = modifier,
            )
        } else {
            MarkdownText(
                text = message.content,
                textColor = textColor,
                modifier = modifier,
            )
        }
    }
}

object ToolCallRenderer : MessageContentRenderer {
    override fun canRender(message: UiMessage) =
        !message.toolCalls.isNullOrEmpty()

    @Composable
    override fun Render(
        message: UiMessage,
        textColor: Color,
        modifier: Modifier,
        onGeneratedUiMessage: ((String) -> Unit)?,
    ) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (message.content.isNotBlank()) {
                MarkdownText(text = message.content, textColor = textColor)
            }
            message.toolCalls?.takeIf { it.isNotEmpty() }?.let { toolCalls ->
                // Wrap at the call-site so MessageToolCalls receives a stable
                // ImmutableList param (o7ob.2.6). UiMessage still uses raw List
                // to avoid rippling the migration through MessageMapper.
                val stableToolCalls = remember(toolCalls) {
                    toolCalls.toImmutableList()
                }
                MessageToolCalls(toolCalls = stableToolCalls)
            }
        }
    }
}

@Composable
private fun GeneratedUiFallbackCard(component: com.letta.mobile.data.model.UiGeneratedComponent) {
    val fontScale = LocalChatFontScale.current
    GeneratedUiCard(title = component.name) {
        component.fallbackText?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium.scaledBy(fontScale))
        }
        Text(
            text = component.propsJson,
            style = MaterialTheme.typography.bodySmall.scaledBy(fontScale),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

val defaultRenderers = listOf(GeneratedUiRenderer, ToolCallRenderer, TextMessageRenderer)

fun resolveRenderer(message: UiMessage): MessageContentRenderer {
    return defaultRenderers.firstOrNull { it.canRender(message) } ?: TextMessageRenderer
}
