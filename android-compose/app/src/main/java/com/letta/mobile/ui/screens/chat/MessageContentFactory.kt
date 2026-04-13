package com.letta.mobile.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.components.MarkdownText
import com.letta.mobile.ui.theme.chatTypography

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
                MessageToolCalls(toolCalls = toolCalls)
            }
        }
    }
}

@Composable
private fun GeneratedUiFallbackCard(component: com.letta.mobile.data.model.UiGeneratedComponent) {
    GeneratedUiCard(title = component.name) {
        component.fallbackText?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = component.propsJson,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

val defaultRenderers = listOf(GeneratedUiRenderer, ToolCallRenderer, TextMessageRenderer)

fun resolveRenderer(message: UiMessage): MessageContentRenderer {
    return defaultRenderers.firstOrNull { it.canRender(message) } ?: TextMessageRenderer
}
