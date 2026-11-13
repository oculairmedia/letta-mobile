package com.letta.mobile.ui.canvas.mount

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.letta.mobile.data.canvas.mount.client.CanvasBoardSwitchGate
import com.letta.mobile.data.canvas.mount.client.CanvasModeFocus
import com.letta.mobile.data.canvas.mount.client.CanvasPickerState
import com.letta.mobile.data.canvas.mount.client.ConversationBoardSwitch
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasPicker
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasState
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasTarget
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.LocalCanvasBoardSave
import com.letta.mobile.ui.chat.surface.LocalChatCanvasOnBoard
import com.letta.mobile.ui.shell.sidebar.LocalShellCanvasMounts
import com.letta.mobile.ui.shell.sidebar.ShellCanvasPane
import kotlinx.coroutines.delay

/**
 * The window's canvas picker: which board each conversation shows and the side menu's pick.
 * Null (the default) is a host without mounts, where every conversation shows its own board.
 */
val LocalConversationCanvasPicker = staticCompositionLocalOf<ConversationCanvasPicker?> { null }

/** Gives every view inside [content] the window's [picker], and the side menu its view of it. */
@Composable
fun ProvideConversationCanvasPicker(picker: ConversationCanvasPicker?, content: @Composable () -> Unit) {
    val menu = remember(picker) { picker?.let(::PickerShellCanvasMounts) }
    CompositionLocalProvider(
        LocalConversationCanvasPicker provides picker,
        LocalShellCanvasMounts provides menu,
        content = content,
    )
}

/**
 * The board seat of one conversation's page, the same on every host: [content] draws the board
 * for the mounted canvas it is given, or the conversation's own board for null. It opens on the
 * own board at once and switches when the host's mount says otherwise, saving the board on screen
 * first (see [ConversationBoardSwitch]); each board is drawn under its own key, so a switch disposes
 * the old board only after its save. While the page is composed it tells the window's
 * [CanvasModeFocus] it is on screen (with its seat, so a pick saves this board before switching it),
 * and whether it is in canvas mode, so a pick in the side menu mounts on this conversation (and knows
 * whether to open a pane too).
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
    ReportOnScreen(picker.focus, target, switch)
    ReportCanvasMode(picker.focus, target)
    val shown by switch.shown.collectAsState()
    CompositionLocalProvider(LocalCanvasBoardSave provides gate::attach) {
        key(shown) { content(shown) }
    }
}

@Composable
private fun ReportOnScreen(focus: CanvasModeFocus, target: ConversationCanvasTarget, switch: ConversationBoardSwitch) {
    DisposableEffect(focus, target, switch) {
        focus.show(target, switch)
        onDispose { focus.hide(target) }
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

/**
 * A host's side pane showing [paneCanvas], closed by [close] when the conversation on screen enters
 * canvas mode with that board on show behind the chat (or switches to it while in canvas mode): the
 * board shows it now, so the pane would be a second copy. Only the board actually on show counts,
 * not the mount asked for: while the board before it cannot be saved the pane stays. A pane opened
 * while the board is already on show is left alone. Nothing happens without the window's picker.
 */
@Composable
fun CloseCanvasPaneShownOnBoard(paneCanvas: CanvasId?, close: () -> Unit) {
    val picker = LocalConversationCanvasPicker.current ?: return
    val state by remember(picker) { picker.state }.collectAsState(CanvasPickerState())
    val shown by remember(picker) { picker.focus.shownBoard }.collectAsState(null)
    val pane by rememberUpdatedState(paneCanvas)
    val onClose by rememberUpdatedState(close)
    val mountedOnBoard = shown.takeIf { state.canvasMode }
    LaunchedEffect(mountedOnBoard) {
        if (mountedOnBoard != null && mountedOnBoard == pane) onClose()
    }
}

/** [CloseCanvasPaneShownOnBoard] for a host's side [pane] of canvases. */
@Composable
fun CloseCanvasPaneShownOnBoard(pane: ShellCanvasPane) {
    CloseCanvasPaneShownOnBoard(pane.activeSession?.canvasId, pane::close)
}

private const val SWITCH_RETRY_MS = 2_000L
