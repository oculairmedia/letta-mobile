package com.letta.mobile.ui.shell.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.chat.surface.LocalChatCanvasBringToBoard
import com.letta.mobile.ui.shell.sidebar.LocalShellCanvasMounts
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelActions
import com.letta.mobile.ui.shell.sidebar.rememberCanvasMountState
import com.letta.mobile.ui.shell.sidebar.rememberCanvasPicks

/**
 * Lets a chat card's "Show on canvas" switch the conversation's board to the artifact's canvas
 * through the side menu's own pick ([panel]'s verbs, the board on show saved first, the same
 * notices), before it frames the artifact. Without the window's canvas mounts nothing is provided
 * and the board frames as it is.
 */
@Composable
internal fun ProvideShowOnCanvas(panel: ShellAgentPanelActions, content: @Composable () -> Unit) {
    val mounts = LocalShellCanvasMounts.current
    val now by rememberUpdatedState(rememberCanvasMountState())
    val picks = rememberCanvasPicks(panel)
    val bringToBoard = remember(mounts, picks) {
        mounts?.let { { canvas: CanvasId, onBoard: () -> Unit -> picks.bringToBoard(canvas, now, onBoard) } }
    }
    CompositionLocalProvider(LocalChatCanvasBringToBoard provides bringToBoard, content = content)
}
