package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import com.letta.mobile.data.canvas.CanvasMimeType
import com.letta.mobile.data.canvas.CanvasShare
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.CanvasCameraRequest
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceIntent

/**
 * What the shared page hands the host's canvas. The canvas is the
 * conversation's own board, so its "share to chat" and "back" act on this conversation and
 * this page's mode directly: no staging queue, no navigation.
 */
/**
 * The window's way to put a canvas on the conversation's board before "Show on canvas" frames it:
 * called with the receipt's canvas and what to do once it is the board on show (never called when
 * the switch was refused). Null (the default) is a window that never switches: the board frames as is.
 */
val LocalChatCanvasBringToBoard = staticCompositionLocalOf<((CanvasId, () -> Unit) -> Unit)?> { null }

/** The page's [ChatCanvasActions], switching boards through the window's [LocalChatCanvasBringToBoard]. */
@Composable
internal fun rememberChatCanvasActions(
    actions: ChatActions,
    onIntent: (ChatSurfaceIntent) -> Unit,
    shareFailedMessage: String,
): ChatCanvasActions {
    val bringToBoard = LocalChatCanvasBringToBoard.current
    return remember(actions, onIntent, shareFailedMessage, bringToBoard) {
        ChatCanvasActions(actions, onIntent, shareFailedMessage, bringToBoard)
    }
}

@Stable
class ChatCanvasActions internal constructor(
    private val actions: ChatActions,
    private val onIntent: (ChatSurfaceIntent) -> Unit,
    private val shareFailedMessage: String,
    private val bringToBoard: ((CanvasId, () -> Unit) -> Unit)? = null,
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

    /**
     * Where the board's camera is asked to go. The host hands it to its CanvasWorkspace
     * (`cameraRequest`), which frames the target once the board is loaded and clears it.
     */
    val camera: CanvasCameraRequest = CanvasCameraRequest()

    /**
     * "Show on canvas" on a canvas_compose card. An artifact on another canvas than the board on show
     * first switches the conversation's board to it, as a pick from the side menu does
     * ([LocalChatCanvasBringToBoard]); a refused switch frames nothing. Then it frames the artifact's
     * bounds on the board (when the receipt has them) and brings the canvas forward with
     * [ChatSurfaceIntent.OpenCanvas], which is a no-op where the canvas already shows.
     */
    fun showArtifact(receipt: CanvasArtifactReceipt) {
        val canvas = receipt.canvasId?.let(::CanvasId)
        val switch = bringToBoard
        if (canvas == null || switch == null) frameAndOpen(receipt) else switch(canvas) { frameAndOpen(receipt) }
    }

    private fun frameAndOpen(receipt: CanvasArtifactReceipt) {
        receipt.bounds?.let { b -> camera.frame(receipt.canvasId, Rect(b.x, b.y, b.x + b.width, b.y + b.height)) }
        onIntent(ChatSurfaceIntent.OpenCanvas)
    }
}
