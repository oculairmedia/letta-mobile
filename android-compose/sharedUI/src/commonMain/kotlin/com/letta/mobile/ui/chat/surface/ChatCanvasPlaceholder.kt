package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_canvas_pending
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: what the docked canvas shows before the chat has a conversation. A
 * board belongs to its conversation, so none is created (and later abandoned) for a new chat;
 * the first send creates the conversation, and the host then opens that conversation's board.
 */
@Composable
fun ChatCanvasPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(LettaDimens.Space.lg), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(Res.string.chat_surface_canvas_pending),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The key a host scopes a conversation's canvas state by; null while there is no conversation. */
fun chatCanvasKey(conversationId: String?): String? = conversationId?.takeIf { it.isNotBlank() }?.let { "canvas:$it" }
