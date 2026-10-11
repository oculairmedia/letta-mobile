package com.letta.mobile.feature.chat.screen

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.data.chat.display.ChatTextSize
import com.letta.mobile.data.chat.display.TranscriptInput
import com.letta.mobile.data.chat.display.TranscriptMarkdown
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsActions
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsState

/**
 * letta-mobile-zxk2n.5 (G91): the shared drawer's Chat options on Android: the display toggles
 * (kept by [SharedNavDrawerViewModel]), the text size over the chat's font scale, and Download
 * transcript through the share sheet. Auto-pin is not offered here yet (follow-up bead): Android
 * creates a new chat on its first send, outside the drawer.
 */
@Composable
internal fun rememberAndroidChatOptionsState(state: AgentScaffoldRuntimeState, drawer: SharedNavDrawerViewModel): ShellChatOptionsState {
    val display by drawer.displayOptions.collectAsStateWithLifecycle()
    val fontScale by state.params.viewModel.chatFontScale.collectAsStateWithLifecycle()
    val hasChat = state.conversationId != null
    return remember(display, fontScale, hasChat) {
        ShellChatOptionsState(
            display = display,
            textSize = ChatTextSize.nearest(fontScale ?: 1f),
            canDownloadTranscript = hasChat,
            offersAutoPin = false,
        )
    }
}

@Composable
internal fun rememberAndroidChatOptionsActions(state: AgentScaffoldRuntimeState, drawer: SharedNavDrawerViewModel): ShellChatOptionsActions {
    val context = LocalContext.current
    return remember(state, drawer, context) {
        ShellChatOptionsActions(
            onDisplayChange = drawer::setDisplayOptions,
            onTextSizeChange = { size -> state.params.viewModel.setChatFontScale(size.fontScale) },
            onDownloadTranscript = { shareTranscript(context, transcriptOf(state, drawer)) },
        )
    }
}

private fun transcriptOf(state: AgentScaffoldRuntimeState, drawer: SharedNavDrawerViewModel): TranscriptInput {
    val title = state.drawerConversations.firstOrNull { it.id.value == state.conversationId }?.summary
    return TranscriptInput(
        title = title?.takeIf { it.isNotBlank() } ?: "Conversation",
        agentName = state.agentName.ifBlank { "Agent" },
        messages = state.params.viewModel.uiState.value.messages,
        options = drawer.displayOptions.value,
    )
}

/** The transcript as Markdown text through the share sheet; a toast when nothing can take it. */
internal fun shareTranscript(context: Context, transcript: TranscriptInput) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, TranscriptMarkdown.fileName(transcript.title))
        putExtra(Intent.EXTRA_TEXT, TranscriptMarkdown.build(transcript))
    }
    val chooser = Intent.createChooser(send, "Share transcript").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can take the transcript", Toast.LENGTH_SHORT).show()
    }
}
