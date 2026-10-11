package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.display.ChatDisplayOptions
import com.letta.mobile.data.chat.display.TranscriptInput
import com.letta.mobile.data.chat.display.TranscriptMarkdown
import com.letta.mobile.data.chat.runtime.displayTitle
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** A transcript ready to save: its suggested file name and its Markdown. */
internal data class DesktopTranscript(val fileName: String, val markdown: String)

/**
 * letta-mobile-zxk2n.5 (G91): "Download transcript" on the desktop: the shared Markdown builder,
 * then a native Save dialog. The transcript holds the messages the window has loaded.
 */
internal object DesktopTranscriptExport {
    /** The selected conversation as a transcript, or null when no conversation is selected. */
    fun transcriptOf(state: DesktopChatSurfaceState, options: ChatDisplayOptions): DesktopTranscript? {
        val selectedId = state.selectedConversationId ?: return null
        val conversation = state.conversations.firstOrNull { it.id == selectedId } ?: return null
        val title = conversation.displayTitle()
        val markdown = TranscriptMarkdown.build(
            TranscriptInput(
                title = title,
                agentName = conversation.agentName,
                messages = state.messagesByConversationId[selectedId].orEmpty(),
                options = options,
            ),
        )
        return DesktopTranscript(TranscriptMarkdown.fileName(title), markdown)
    }

    /** Asks where to save [transcript] (suggesting its file name) and writes it; false when cancelled. */
    fun save(transcript: DesktopTranscript): Boolean {
        val dialog = FileDialog(null as Frame?, "Save transcript", FileDialog.SAVE).apply {
            file = transcript.fileName
            isVisible = true
        }
        val directory = dialog.directory ?: return false
        val chosen = dialog.file ?: return false
        val target = File(directory, if (chosen.endsWith(".md")) chosen else "$chosen.md")
        target.writeText(transcript.markdown, Charsets.UTF_8)
        return true
    }
}
