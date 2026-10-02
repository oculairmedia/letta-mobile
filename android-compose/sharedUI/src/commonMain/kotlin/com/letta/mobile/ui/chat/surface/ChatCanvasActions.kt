package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Stable
import com.letta.mobile.data.canvas.CanvasMimeType
import com.letta.mobile.data.canvas.CanvasShare
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent

/**
 * letta-mobile-bglj6.1: what the shared page hands the host's canvas. The canvas is the
 * conversation's own board, so its "share to chat" and "back" act on this conversation and
 * this page's mode directly: no staging queue, no navigation.
 */
@Stable
class ChatCanvasActions internal constructor(
    private val actions: ChatActions,
    private val onIntent: (ChatSurfaceIntent) -> Unit,
    private val shareFailedMessage: String,
) {
    /**
     * Attaches the exported board to the draft and opens the full-screen page to finish the
     * message. An image that cannot be prepared reports a composer error and leaves the person
     * on the canvas (the docked composer shows the error).
     */
    fun shareToChat(bytes: ByteArray, mimeType: String) {
        CanvasShare.packageForChat(bytes, CanvasMimeType.fromValue(mimeType))
            .onSuccess { image ->
                actions.attachImage(image)
                onIntent(ChatSurfaceIntent.Expand)
            }
            .onFailure { actions.reportComposerError(shareFailedMessage) }
    }

    /** The canvas's own back control: back to the conversation. */
    fun back() = onIntent(ChatSurfaceIntent.Expand)
}
