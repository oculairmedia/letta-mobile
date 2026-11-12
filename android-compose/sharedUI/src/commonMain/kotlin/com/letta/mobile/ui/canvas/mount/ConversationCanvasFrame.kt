package com.letta.mobile.ui.canvas.mount

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.canvas.mount.client.CanvasBoardSwitchGate
import com.letta.mobile.data.canvas.mount.client.CanvasModeFocus
import com.letta.mobile.data.canvas.mount.client.ConversationBoardSwitch
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasPicker
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasState
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasTarget
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.delay

/**
 * The window's canvas picker: which board each conversation shows and the side menu's pick.
 * Null (the default) is a host without mounts, where every conversation shows its own board.
 */
val LocalConversationCanvasPicker = staticCompositionLocalOf<ConversationCanvasPicker?> { null }

/** The save a switch waits for, attached by the board on screen inside a [ConversationCanvasFrame]. */
val LocalCanvasBoardSwitchGate = staticCompositionLocalOf<CanvasBoardSwitchGate?> { null }

/** True while the chat page shows its board with the chat over it (canvas mode); the chat surface provides it. */
val LocalChatCanvasOnBoard = compositionLocalOf { false }

/** Gives every view inside [content] the window's [picker]. */
@Composable
fun ProvideConversationCanvasPicker(picker: ConversationCanvasPicker?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalConversationCanvasPicker provides picker, content = content)
}

/**
 * The board seat of one conversation's page, the same on every host: [content] draws the board
 * for the mounted canvas it is given, or the conversation's own board for null. It opens on the
 * own board at once and switches when the host's mount says otherwise, saving the board on screen
 * first (see [ConversationBoardSwitch]); each board is drawn under its own key, so a switch disposes
 * the old board only after its save. While the page is in canvas mode it tells the window's
 * [CanvasModeFocus], so a pick in the side menu mounts on this conversation.
 *
 * Without a picker or a [target] (a new chat) the own board is drawn and nothing is reported.
 */
@Composable
fun ConversationCanvasFrame(target: ConversationCanvasTarget?, content: @Composable (mounted: CanvasId?) -> Unit) {
    val picker = LocalConversationCanvasPicker.current
    if (picker == null || target == null) {
        content(null)
        return
    }
    val gate = remember(target) { CanvasBoardSwitchGate() }
    val switch = remember(target) { ConversationBoardSwitch(gate) }
    val state by remember(picker, target) { picker.resolver.state(target) }.collectAsState(ConversationCanvasState.Resolving)
    LaunchedEffect(switch, state) {
        // A failed save keeps the board on screen; the switch is asked again until it lands or the mount changes.
        while (!switch.follow(state)) delay(SWITCH_RETRY_MS)
    }
    ReportCanvasMode(picker.focus, target)
    val shown by switch.shown.collectAsState()
    CompositionLocalProvider(LocalCanvasBoardSwitchGate provides gate) {
        key(shown) { content(shown) }
    }
}

@Composable
private fun ReportCanvasMode(focus: CanvasModeFocus, target: ConversationCanvasTarget) {
    val onBoard = LocalChatCanvasOnBoard.current
    DisposableEffect(focus, target, onBoard) {
        if (onBoard) focus.enter(target)
        onDispose { focus.leave(target) }
    }
}

private const val SWITCH_RETRY_MS = 2_000L
