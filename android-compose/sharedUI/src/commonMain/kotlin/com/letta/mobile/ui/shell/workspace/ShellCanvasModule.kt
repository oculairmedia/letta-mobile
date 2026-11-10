package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.CanvasWorkspace
import com.letta.mobile.ui.canvas.LocalCanvasSessionRegistry
import com.letta.mobile.ui.canvas.boards.CanvasBoardSource
import com.letta.mobile.ui.canvas.boards.LocalCanvasBoardSource
import com.letta.mobile.ui.shell.modules.UnavailableModule
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaSpinner
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.coroutines.CancellationException

/** The test tag of the module showing the board [canvasId]. */
internal fun shellCanvasModuleTag(canvasId: CanvasId): String = "shell-canvas-module:${canvasId.value}"

/** The test tag of a canvas module still opening its board. */
internal const val SHELL_CANVAS_MODULE_OPENING_TAG = "shell-canvas-module-opening"

/** What a canvas module has of its board. */
private sealed interface ModuleBoard {
    data object Opening : ModuleBoard

    data object Missing : ModuleBoard

    class Open(val session: CanvasSession) : ModuleBoard
}

/**
 * The board [board] as a shell module, at whatever size its place gives it; the board's own
 * chrome follows that size, not the window's. A board already live in this window (open in the
 * page, say) is shown through that same session, so the two never hold diverging copies; any
 * other is opened through the host's [LocalCanvasBoardSource]. No board, one no store holds, or a
 * host that cannot open boards shows the shell's unavailable module.
 */
@Composable
internal fun ShellCanvasModule(board: CanvasId?, modifier: Modifier) {
    when (val shown = rememberModuleBoard(board, LocalCanvasSessionRegistry.current, LocalCanvasBoardSource.current)) {
        ModuleBoard.Opening -> Box(modifier.testTag(SHELL_CANVAS_MODULE_OPENING_TAG), contentAlignment = Alignment.Center) {
            LettaSpinner(size = LettaDimens.Control.spinnerLg)
        }
        ModuleBoard.Missing -> UnavailableModule(modifier)
        // The board is the whole module: no back arrow, its title names it.
        is ModuleBoard.Open -> CanvasWorkspace(
            modifier = modifier.testTag(shellCanvasModuleTag(shown.session.canvasId)),
            session = shown.session,
            onNavigateBack = null,
        )
    }
}

@Composable
private fun rememberModuleBoard(id: CanvasId?, registry: CanvasSessionRegistry?, source: CanvasBoardSource?): ModuleBoard {
    val initial = remember(id, registry, source) { liveBoard(id, registry, source) }
    val board by produceState(initial, initial, source) {
        value = initial
        if (initial == ModuleBoard.Opening && id != null && source != null) value = openBoard(source, id)
    }
    return board
}

/** The board as this window already has it: its live session, missing, or still to open. */
private fun liveBoard(id: CanvasId?, registry: CanvasSessionRegistry?, source: CanvasBoardSource?): ModuleBoard {
    if (id == null) return ModuleBoard.Missing
    registry?.get(id)?.let { return ModuleBoard.Open(it) }
    return if (source == null) ModuleBoard.Missing else ModuleBoard.Opening
}

/** The board [source] opens; a store that fails to read it is shown like one without it. */
private suspend fun openBoard(source: CanvasBoardSource, id: CanvasId): ModuleBoard {
    val opened = runCatching { source.open(id) }
    val failure = opened.exceptionOrNull()
    if (failure is CancellationException) throw failure
    if (failure != null) Telemetry.error("ShellCanvasModule", "board.open.failed", failure, "canvasId" attr id.value)
    return opened.getOrNull()?.let(ModuleBoard::Open) ?: ModuleBoard.Missing
}
