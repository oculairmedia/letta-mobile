package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasCheckpoint
import com.letta.mobile.data.canvas.CanvasDeletedElement
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.ui.theme.LettaDimens
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState
import kotlinx.coroutines.launch

private const val ZOOM_STEP = 1.25f

/** The drawing tools and their properties, as every bar that offers them shares them. */
internal class CanvasBoardTools(
    val controlsBar: ControlsBarState,
    val properties: CanvasProperties,
    val dispatch: (ControlsBarIntent) -> Unit,
    val dispatchProperty: (CanvasPropertyIntent) -> Unit,
)

/** What the board's chrome offers, built once per composition for whichever bar shows it. */
internal class CanvasBoardChromeActions(
    val zoom: CanvasZoom,
    val share: (() -> Unit)?,
    val menu: CanvasMenuActions,
    val background: CanvasBackgroundActions,
    val undo: CanvasUndoActions,
    val tools: CanvasBoardTools,
    val insert: BoardInsertActions,
)

/** The board's chrome over the drawing: its bars, menus and dialogs, in drawing order. */
@Composable
internal fun BoxScope.CanvasBoardChrome(board: CanvasBoard, insert: BoardInsertActions) {
    val actions = board.chromeActions(insert)
    // With a title: one bar across the top (back and title, the sync status, then the board's
    // actions). Without one (the canvas is the page, e.g. under the shared chat): just the
    // actions, as a compact pill in the top-right corner over an uncovered board. Under the
    // phone's chat page there is neither: the top of the board is clear, and the actions end the
    // tool bar at the foot.
    if (!board.view.actionsInFoot) CanvasBoardHeader(board, actions)
    CanvasBoardHistory(board)
    val selection = board.selectionChrome()
    CanvasBoardSelectionLayer(board, actions, selection)
    CanvasBoardToolRail(board, actions.tools)
    CanvasBoardNoteEditor(board, selection.expanded)
    CanvasBoardFootBar(board, actions, selection)
    CanvasBoardContextMenu(board, insert)
}

private fun CanvasBoard.chromeActions(insert: BoardInsertActions): CanvasBoardChromeActions {
    val tools = boardTools()
    return CanvasBoardChromeActions(
        zoom = zoomActions(),
        share = shareAction(),
        menu = menuActions(),
        background = backgroundActions(),
        undo = CanvasUndoActions(
            canUndo = tools.controlsBar.canUndo,
            canRedo = tools.controlsBar.canRedo,
            onUndo = { undoBoard() },
            onRedo = { redoBoard() },
        ),
        tools = tools,
        insert = insert,
    )
}

/**
 * The buttons answer for the BOARD: undo and redo are the board's, not the drawing's. A note edit
 * and a stroke are both things the person did, and they undo in the order they were done.
 */
private fun CanvasBoard.boardTools(): CanvasBoardTools {
    val controlsBar = CanvasControlsBridge.buildControlsBarState(
        state = state,
        canUndo = flows.canUndo.value || flows.historyCanUndo.value,
        canRedo = flows.canRedo.value || flows.historyCanRedo.value,
    )
    return CanvasBoardTools(
        controlsBar = controlsBar,
        properties = CanvasControlsBridge.buildProperties(state),
        dispatch = { intent -> dispatchControl(intent) },
        dispatchProperty = { intent ->
            CanvasControlsBridge.dispatchProperty(controller = controller, intent = intent, state = state)
        },
    )
}

private fun CanvasBoard.dispatchControl(intent: ControlsBarIntent) {
    when (intent) {
        ControlsBarIntent.Undo -> undoBoard()
        ControlsBarIntent.Redo -> redoBoard()
        else -> CanvasControlsBridge.dispatchIntent(controller = controller, intent = intent, hasSelection = view.hasSelection)
    }
}

private fun CanvasBoard.zoomActions(): CanvasZoom {
    val center = view.boardCenter
    return CanvasZoom(
        scalePercent = state.viewport.scalePercent,
        onZoomOut = { controller.zoomBy(1f / ZOOM_STEP, center) },
        onZoomIn = { controller.zoomBy(ZOOM_STEP, center) },
        // Fit everything on the board (elements and notes) with padding; an empty board just
        // goes back to 100% at the origin.
        onReset = { if (fitToContent()) ui.statusMessage = "Fitted to content" },
        onActualSize = { controller.zoomTo(1f, center) },
    )
}

private fun CanvasBoard.shareAction(): (() -> Unit)? {
    if (host.callbacks.onShareToChat == null) return null
    return {
        ui.isSharingToChat = true
        controller.exportSvg()
    }
}

private fun CanvasBoard.menuActions(): CanvasMenuActions {
    return CanvasMenuActions(
        onImportBuildCycle = {
            controller.importPath(CanvasSamples.buildCycleJson)
            ui.statusMessage = "Imported Build Cycle sample"
        },
        onImportDailyLoop = {
            controller.importPath(CanvasSamples.dailyLoopJson)
            ui.statusMessage = "Imported Daily Loop sample"
        },
        onExportJson = { exportJsonToHost() },
        onExportSvg = { controller.exportSvg() },
        onClear = {
            controller.reset()
            ui.statusMessage = "Cleared canvas"
        },
    )
}

/**
 * A board saves on its own; an export is for somewhere else, so it carries its images' bytes
 * rather than refs nothing there can resolve.
 */
private fun CanvasBoard.exportJsonToHost() {
    val handler = host.callbacks.onExportJson
    if (handler == null) {
        controller.exportJson()
    } else {
        scope.launch { exportStandalone(handler) }
    }
}

private fun CanvasBoard.backgroundActions(): CanvasBackgroundActions {
    return CanvasBackgroundActions(
        color = state.bgColor,
        onColor = { color ->
            controller.setBgColor(color)
            ui.statusMessage = "Background changed"
        },
        pattern = docs.backgroundPattern,
        onPattern = { pattern -> chooseBackgroundPattern(pattern) },
    )
}

private fun CanvasBoard.chooseBackgroundPattern(pattern: CanvasBackgroundPattern) {
    val s = session
    if (s != null) {
        scope.launch { runCatching { s.setBackgroundPattern(pattern) } }
    } else {
        ui.localPattern = pattern
    }
    ui.statusMessage = "Background pattern: ${pattern.kind}"
}

/** How many checkpoints the board has; null without a session, which keeps none. */
private fun CanvasBoard.checkpointCount(): Int? {
    if (session == null) return null
    return flows.sharing.checkpoints.value.size
}

private fun CanvasBoard.openHistoryAction(): (() -> Unit)? {
    if (session == null) return null
    return { ui.showHistoryDialog = true }
}

@Composable
private fun BoxScope.CanvasBoardHeader(board: CanvasBoard, actions: CanvasBoardChromeActions) {
    val options = board.host.chrome
    CanvasHeaderBar(
        modifier = headerPlacement(options.showTitle)
            .windowInsetsPadding(board.view.insets.chrome)
            .padding(CANVAS_CHROME_INSET).canvasChrome(board.ui.chromeRegions)
            .testTag(CANVAS_ACTIONS_TAG),
    ) {
        if (options.showTitle) CanvasBoardTitle(board)
        board.flows.sharing.syncHealth.value?.let { health -> CanvasSyncStatusBadge(health = health) }
        if (options.showTitle) Spacer(modifier = Modifier.weight(1f))
        CanvasActionsPill(
            zoom = actions.zoom,
            checkpointCount = board.checkpointCount(),
            onHistory = board.openHistoryAction(),
            onShare = actions.share,
            menu = actions.menu,
            background = actions.background,
            undo = actions.undo.takeIf { board.view.compact },
        )
        options.headerTrailing?.invoke()
    }
}

private fun BoxScope.headerPlacement(showTitle: Boolean): Modifier {
    if (showTitle) return Modifier.align(Alignment.TopCenter).fillMaxWidth()
    return Modifier.align(Alignment.TopEnd)
}

@Composable
private fun RowScope.CanvasBoardTitle(board: CanvasBoard) {
    val sessionDoc = board.flows.sessionDoc.value
    CanvasTitlePill(
        title = sessionDoc?.title ?: "Canvas",
        revision = sessionDoc?.revision,
        onNavigateBack = board.navigateBackAction(),
        compact = board.view.compact,
        modifier = Modifier.weight(1f, fill = false),
    )
}

private fun CanvasBoard.navigateBackAction(): (() -> Unit)? {
    val back = host.callbacks.onNavigateBack ?: return null
    return {
        if (session != null) controller.exportJson()
        back()
    }
}

@Composable
private fun CanvasBoardHistory(board: CanvasBoard) {
    val ui = board.ui
    val session = board.session
    LaunchedEffect(ui.showHistoryDialog, session, board.flows.sessionDoc.value?.revision) {
        board.refreshDeletedHistory()
    }
    CanvasHistoryDialog(
        state = CanvasHistoryDialogState(
            show = ui.showHistoryDialog && session != null,
            checkpoints = board.flows.sharing.checkpoints.value,
            deletedElements = board.kept.deletedHistory,
            compact = board.view.compact,
        ),
        onRestoreElement = { element -> board.restoreElement(element) },
        onDismiss = { ui.showHistoryDialog = false },
        onRestore = { checkpoint -> board.restoreCheckpoint(checkpoint) },
    )
}

private suspend fun CanvasBoard.refreshDeletedHistory() {
    if (ui.showHistoryDialog) kept.deletedHistory = session?.deletedElements().orEmpty()
}

private fun CanvasBoard.restorerContext(): CanvasHistoryRestorer.WorkspaceContext {
    return CanvasHistoryRestorer.WorkspaceContext(session, host.assets, controller, kept.history)
}

private fun CanvasBoard.keepRestoredScene(restored: CanvasHistoryRestorer.RestoredSceneState) {
    ui.lastExportedJson = restored.sceneJson
    ui.lastDrawing = restored.cleanDrawing
    ui.lastSavedElements = restored.elements
}

private fun CanvasBoard.restoreFailed(message: String) {
    ui.statusMessage = "Restore failed: $message"
}

private fun CanvasBoard.restoreElement(element: CanvasDeletedElement) {
    scope.launch {
        CanvasHistoryRestorer.restoreElement(restorerContext(), element, { elementRestored(it) }) { restoreFailed(it) }
    }
}

private fun CanvasBoard.elementRestored(restored: CanvasHistoryRestorer.RestoredSceneState) {
    keepRestoredScene(restored)
    kept.deletedHistory = restored.deletedHistory
    ui.statusMessage = restored.message
}

private fun CanvasBoard.restoreCheckpoint(checkpoint: CanvasCheckpoint) {
    scope.launch {
        CanvasHistoryRestorer.restoreCheckpoint(restorerContext(), checkpoint, { checkpointRestored(it) }) { restoreFailed(it) }
    }
}

private fun CanvasBoard.checkpointRestored(restored: CanvasHistoryRestorer.RestoredSceneState) {
    keepRestoredScene(restored)
    ui.statusMessage = restored.message
    ui.showHistoryDialog = false
}

/**
 * The tool rail down the left, clear of the title pill above and the foot row below. Our own:
 * the drawbox-ui one loads drawables its Android artifact never ships (letta-mobile-r5f3r). See
 * CanvasControlsBar.
 */
@Composable
private fun BoxScope.CanvasBoardToolRail(board: CanvasBoard, tools: CanvasBoardTools) {
    if (board.view.resolvedLayout != CanvasLayout.EXPANDED) return
    CanvasControlsBar(
        state = tools.controlsBar,
        dispatch = tools.dispatch,
        properties = tools.properties,
        dispatchProperty = tools.dispatchProperty,
        onAddNote = board.addNoteAtCenterAction(),
        modifier = Modifier
            .align(Alignment.CenterStart)
            .windowInsetsPadding(board.view.insets.chrome)
            .padding(start = CANVAS_CHROME_INSET, top = 72.dp, bottom = 64.dp)
            .canvasChrome(board.ui.chromeRegions),
    )
}

private fun CanvasBoard.addNoteAtCenterAction(): (() -> Unit)? {
    if (session == null) return null
    return { addNoteAt(state.viewport.screenToWorld(view.boardCenter)) }
}

/** A note opened large sits over the board, under the foot bar so formatting stays reachable. */
@Composable
private fun CanvasBoardNoteEditor(board: CanvasBoard, expanded: CanvasSceneDocument?) {
    val session = board.session
    if (expanded == null || session == null) return
    val ui = board.ui
    CanvasNoteEditorPanel(
        session = session,
        document = expanded,
        onClose = { ui.expandedNoteId = null },
        onToolbar = { ui.noteToolbar = it },
        chromeRegions = ui.chromeRegions,
        compact = board.view.compact,
        insets = board.view.insets.chrome,
        actions = board.noteEditorActions(expanded),
    )
}

private fun CanvasBoard.noteEditorActions(expanded: CanvasSceneDocument): NoteEditorActions {
    return NoteEditorActions(
        canUndo = flows.canUndo.value || flows.historyCanUndo.value,
        canRedo = flows.canRedo.value || flows.historyCanRedo.value,
        onUndo = { undoBoard() },
        onRedo = { redoBoard() },
        onDuplicate = {
            ui.activeNoteId = expanded.id
            duplicateFocused()
        },
        onDelete = {
            ui.expandedNoteId = null
            ui.activeNoteId = null
            deleteNote(expanded.id)
        },
    )
}

/**
 * The foot of the board: the active note's formatting bar, centred, above the status line on a
 * desktop and above the tool bar on a phone. Inset from the system bars, the keyboard and the
 * host's chrome, so on a phone the formatting bar rides up with the keyboard.
 */
@Composable
private fun BoxScope.CanvasBoardFootBar(
    board: CanvasBoard,
    actions: CanvasBoardChromeActions,
    selection: CanvasBoardSelection,
) {
    val ui = board.ui
    Column(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .windowInsetsPadding(board.view.insets.chrome).padding(CANVAS_CHROME_INSET)
            .canvasChrome(ui.chromeRegions)
            .onSizeChanged { ui.footHeight[0] = it.height },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        val toolbar = ui.formattingToolbar()
        if (selection.typingOnPhone) CanvasBoardSelectionBar(board, actions, selection, Modifier)
        // An opened note carries its own formatting in its foot bar.
        if (toolbar != null) CanvasFormattingBar(toolbar = toolbar)
        CanvasBoardFootRow(board, actions, selection)
    }
}

/** The active note's formatting controls, unless it is opened large and carries its own. */
private fun CanvasBoardUi.formattingToolbar(): NoteToolbar? {
    val toolbar = noteToolbar ?: return null
    if (activeNoteId == null || expandedNoteId != null) return null
    return toolbar
}

@Composable
private fun ColumnScope.CanvasBoardFootRow(
    board: CanvasBoard,
    actions: CanvasBoardChromeActions,
    selection: CanvasBoardSelection,
) {
    when (board.view.resolvedLayout) {
        CanvasLayout.COMPACT -> if (selection.expanded == null && !selection.typingOnPhone) {
            CanvasBoardCompactToolbar(board, actions)
        }
        else -> CanvasStatusLine(
            text = "Elements: ${board.state.elements.size} | ${board.ui.statusMessage}",
            modifier = Modifier.align(Alignment.Start),
        )
    }
}

@Composable
private fun CanvasBoardCompactToolbar(board: CanvasBoard, actions: CanvasBoardChromeActions) {
    val tools = actions.tools
    CanvasCompactToolbar(
        state = tools.controlsBar,
        properties = tools.properties,
        actions = CompactToolbarActions(
            dispatch = tools.dispatch,
            dispatchProperty = tools.dispatchProperty,
            insert = actions.insert,
            addAt = { board.controller.state.value.viewport.screenToWorld(board.view.boardCenter) },
        ),
        trailing = board.footTrailing(actions),
    )
}

/** The phone chat page's board actions, ending the tool bar at the foot. */
private fun CanvasBoard.footTrailing(actions: CanvasBoardChromeActions): (@Composable () -> Unit)? {
    if (!view.actionsInFoot) return null
    return { CompactBoardActions(undo = actions.undo, overflow = overflowFor(actions)) }
}

private fun CanvasBoard.overflowFor(actions: CanvasBoardChromeActions): CanvasOverflow {
    return CanvasOverflow(
        zoom = actions.zoom,
        checkpointCount = checkpointCount(),
        onHistory = openHistoryAction(),
        menu = actions.menu,
        background = actions.background,
        compact = true,
        onShare = actions.share,
        sync = flows.sharing.syncHealth.value,
        host = view.hostChrome.menu,
    )
}

/** The long-press / right-click menu, while it is open. */
@Composable
private fun CanvasBoardContextMenu(board: CanvasBoard, insert: BoardInsertActions) {
    val request = board.ui.boardMenu ?: return
    val controller = board.controller
    CanvasBoardMenu(
        request = request,
        insert = insert,
        element = BoardElementActions(
            onEditText = { board.editSelectedText() },
            onDuplicate = { board.duplicateFocused() },
            onBringToFront = { controller.bringSelectionToFront() },
            onSendToBack = { controller.sendSelectionToBack() },
            onDelete = { board.deleteFocused() },
        ),
        onDismiss = { board.ui.boardMenu = null },
    )
}

private fun CanvasBoard.editSelectedText() {
    val current = controller.state.value
    current.elements.singleOrNull { it.id in current.selectedIds }?.let { openTextIn(it) }
}
