package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.chat.display.ChatTextSize
import com.letta.mobile.desktop.chat.DesktopChatController
import com.letta.mobile.desktop.chat.DesktopConversationMarks
import com.letta.mobile.desktop.chat.DesktopTranscriptExport
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsActions
import com.letta.mobile.ui.shell.sidebar.ShellChatOptionsState
import com.letta.mobile.ui.shell.sidebar.ShellConversationMarkActions

/**
 * letta-mobile-zxk2n.5: the sidebar's list marks and Chat options, read from the controller's
 * shared prefs: settled chats and markers, the stack's archived chats for the Archived chats
 * dialog, and the dialog's display options, text size (the window's font scale) and auto-pin.
 */
@Composable
internal fun rememberDesktopSidebarListMarks(context: DesktopShellContext, frame: DesktopShellFrame): DesktopSidebarListMarks {
    val marks = context.core.chatController.conversationManagement.marks
    val settled by marks.settled.collectAsState()
    val markers by marks.markers.collectAsState()
    val autoPin by marks.autoPinNewChats.collectAsState()
    val display by marks.displayOptions.collectAsState()
    val fontScale = LocalDesktopChatFontScale.current
    val hasChat = frame.chatState.selectedConversationId != null
    return remember(settled, markers, autoPin, display, fontScale, hasChat, frame.lists.archivedAgentConversations) {
        DesktopSidebarListMarks(
            settledIds = settled,
            markers = markers,
            archivedConversations = frame.lists.archivedAgentConversations,
            chatOptions = ShellChatOptionsState(
                display = display,
                textSize = ChatTextSize.nearest(fontScale),
                autoPinNewChats = autoPin,
                canDownloadTranscript = hasChat,
            ),
        )
    }
}

/** Settle and marker for the sidebar's rows; null when this desktop keeps no marks. */
internal fun DesktopConversationMarks.rowActions(): ShellConversationMarkActions? =
    if (!enabled) {
        null
    } else {
        ShellConversationMarkActions(onSettle = ::setSettled, onSetMarker = ::setMarker, markerChoices = markerChoices)
    }

/** What the Chat options dialog changes on the desktop. */
@Composable
internal fun rememberDesktopChatOptionsActions(controller: DesktopChatController): ShellChatOptionsActions {
    val setFontScale by rememberUpdatedState(LocalDesktopChatFontScaleSetter.current)
    return remember(controller) {
        val marks = controller.conversationManagement.marks
        ShellChatOptionsActions(
            onDisplayChange = marks::setDisplayOptions,
            onTextSizeChange = { size -> setFontScale(size.fontScale) },
            onAutoPinNewChatsChange = marks::setAutoPinNewChats,
            onDownloadTranscript = { downloadTranscript(controller) },
        )
    }
}

private fun downloadTranscript(controller: DesktopChatController) {
    val transcript = DesktopTranscriptExport.transcriptOf(controller.state.value, controller.conversationManagement.marks.displayOptions.value)
        ?: return
    runCatching { DesktopTranscriptExport.save(transcript) }
        .onFailure { controller.conversationManagement.reportError("Couldn't save the transcript: ${it.message ?: it::class.simpleName}") }
}
