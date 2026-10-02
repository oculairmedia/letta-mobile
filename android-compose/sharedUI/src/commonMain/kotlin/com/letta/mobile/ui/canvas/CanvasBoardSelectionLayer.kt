package com.letta.mobile.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasSceneDocument
import io.ak1.drawbox.domain.model.Element
import kotlinx.coroutines.launch

private const val QUICK_ARROW_HEAD_PX = 16f
private const val QUICK_ARROW_HEAD_SPREAD = 0.6f
private val QUICK_ARROW_STROKE = 3.5.dp

/** What is selected and being worked on, as the bars over the board show it. */
internal class CanvasBoardSelection(
    /** Where each note is on screen right now: mid-drag, the card's live frame. */
    val anchorDocuments: List<CanvasSceneDocument>,
    val activeNote: CanvasSceneDocument?,
    val notesSelected: Boolean,
    /** The active note's (or shape label's) bar actions, built once for whichever bar shows them. */
    val noteActions: NoteBarActions?,
    val quickAnchor: Rect?,
    /** The one selected element, when it holds text. */
    val editable: Element?,
    /**
     * Typing into a shape on a phone: the keyboard takes the bottom half, so the tool bar steps
     * aside and the selection bar rides on the keyboard instead of over the shape, the way Miro
     * lays it out. A shape only: a standalone text keeps its usual bars.
     */
    val typingOnPhone: Boolean,
    /** The note opened large. */
    val expanded: CanvasSceneDocument?,
)

@Composable
internal fun CanvasBoard.selectionChrome(): CanvasBoardSelection {
    val anchorDocuments = documents.map { d -> ui.liveNoteFrames[d.id]?.let { d.copy(frame = it) } ?: d }
    val activeNote = ui.activeNoteId?.let { id -> anchorDocuments.firstOrNull { it.id == id } }
    val notesSelected = ui.selectedNoteIds.isNotEmpty()
    val editable = state.elements.singleOrNull { it.id in state.selectedIds }
        ?.takeIf { CanvasWorkspaceSupport.holdsText(it) }
    return CanvasBoardSelection(
        anchorDocuments = anchorDocuments,
        activeNote = activeNote,
        notesSelected = notesSelected,
        noteActions = noteBarActions(activeNote),
        quickAnchor = quickAnchor(activeNote, notesSelected),
        editable = editable,
        typingOnPhone = isTypingOnPhone(editable),
        expanded = documents.firstOrNull { it.id == ui.expandedNoteId },
    )
}

@Composable
private fun CanvasBoard.noteBarActions(activeNote: CanvasSceneDocument?): NoteBarActions? {
    val s = session
    if (activeNote == null || s == null) return null
    val tint = parseHexColor(activeNote.color)
    val plain = tint != null && tint.alpha == 0f
    return NoteBarActions(
        onOpen = { ui.expandedNoteId = activeNote.id },
        onDelete = {
            ui.activeNoteId = null
            deleteNote(activeNote.id)
        },
        style = activeNote.style,
        onStyle = { style ->
            scope.launch {
                recordingDocuments("restyling a note") { runCatching { s.restyleDocument(activeNote.id, style) } }
            }
        },
        color = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh,
        onColor = { color ->
            scope.launch {
                recordingDocuments("recolouring a note") { runCatching { s.recolorDocument(activeNote.id, color.toHex()) } }
            }
        },
        defaultTextColor = noteTextColor(tint, plain),
        plain = plain,
    )
}

@Composable
private fun noteTextColor(tint: Color?, plain: Boolean): Color {
    if (tint == null || plain) return MaterialTheme.colorScheme.onSurface
    return contrastOn(tint)
}

private fun CanvasBoard.quickAnchor(activeNote: CanvasSceneDocument?, notesSelected: Boolean): Rect? {
    val editingId = ui.editingTextId
    return CanvasWorkspaceSupport.quickCreateAnchor(
        QuickCreateAnchorParams(
            state = state,
            activeNote = activeNote?.takeIf { ui.expandedNoteId == null && !notesSelected },
            // A shape just made has the caret in it; its targets stay so the next one can follow.
            editing = editingId != null && editingId !in state.selectedIds,
        ),
    )
}

private fun CanvasBoard.isTypingOnPhone(editable: Element?): Boolean {
    if (!view.compact || ui.editingTextId == null) return false
    return view.hasSelection && editable is Element.Shape
}

/**
 * Properties for the selection, floating on it the way Miro does; top-centre for the closed
 * shape about to be drawn, when there is nothing to float on. With a note active and nothing
 * drawn selected, the bar is the note's. The quick-create targets and the arrow pulled out of
 * one sit with it.
 */
@Composable
internal fun CanvasBoardSelectionLayer(
    board: CanvasBoard,
    actions: CanvasBoardChromeActions,
    selection: CanvasBoardSelection,
) {
    val ui = board.ui
    // The arrow being pulled out, and while its menu is open, the arrow it will become. Drawn
    // first, so it runs out from under the target it was pulled from.
    (ui.quickDrag ?: ui.quickDrop)?.let { pulled -> QuickCreatePullArrow(pulled) }
    selection.quickAnchor?.let { anchor ->
        CanvasQuickCreateTargets(
            anchor = anchor,
            actions = board.quickCreateActions(),
            chromeRegions = ui.chromeRegions,
            modifier = Modifier.fillMaxSize(),
            compact = board.view.compact,
        )
    }
    ui.quickDrop?.let { drop -> QuickCreateDropMenu(board, drop, selection.activeNote) }
    if (board.showsFloatingBar(actions.tools, selection)) CanvasBoardFloatingBar(board, actions, selection)
}

@Composable
private fun QuickCreatePullArrow(pulled: QuickCreateDrag) {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize()) { drawQuickCreateArrow(pulled, tint) }
}

private fun CanvasBoard.quickCreateActions(): QuickCreateActions {
    return QuickCreateActions(
        onCreate = { direction -> quickCreate(direction) },
        onDrag = { ui.quickDrag = it },
        onDrop = { drop -> dropQuickArrow(drop) },
    )
}

/** A pull that barely left the target was a fumbled press, not an arrow. */
private fun CanvasBoard.dropQuickArrow(drop: QuickCreateDrag) {
    if ((drop.to - drop.from).getDistance() <= QUICK_PULL_MIN_PX) return
    ui.quickDrop = drop
    revealDrop(drop)
}

@Composable
private fun QuickCreateDropMenu(board: CanvasBoard, drop: QuickCreateDrag, activeNote: CanvasSceneDocument?) {
    Box(modifier = Modifier.offset { IntOffset(drop.to.x.toInt(), drop.to.y.toInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = { board.ui.quickDrop = null }) {
            quickCreateKinds(activeNote, board.session != null).forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.label) },
                    onClick = {
                        board.ui.quickDrop = null
                        board.quickCreateAt(drop, kind)
                    },
                )
            }
        }
    }
}

private fun quickCreateKinds(activeNote: CanvasSceneDocument?, hasSession: Boolean): List<QuickCreateKind> {
    return QuickCreateKind.entries
        // "Same as this" copies a shape; out of a note it would only be a note.
        .filter { it != QuickCreateKind.SAME || activeNote == null }
        .filter { it != QuickCreateKind.NOTE || hasSession }
}

private fun CanvasBoard.showsFloatingBar(tools: CanvasBoardTools, selection: CanvasBoardSelection): Boolean {
    if (selection.typingOnPhone) return false
    if (view.hasSelection || selection.notesSelected) return true
    if (tools.controlsBar.showFillTarget) return true
    return selection.activeNote != null && ui.expandedNoteId == null
}

@Composable
private fun CanvasBoardFloatingBar(
    board: CanvasBoard,
    actions: CanvasBoardChromeActions,
    selection: CanvasBoardSelection,
) {
    val topInset = with(LocalDensity.current) { board.view.insets.chrome.getTop(this).toDp() }
    AnchoredToSelection(
        anchor = CanvasWorkspaceSupport.barAnchor(
            BarAnchorParams(
                state = board.state,
                documents = selection.anchorDocuments,
                selectedNoteIds = board.ui.selectedNoteIds,
                activeNote = selection.activeNote,
                groupOffset = board.ui.groupOffset,
            ),
        ),
        topClearance = topInset + board.barTopClearance(),
        startClearance = board.view.resolvedLayout.railClearance(),
        // Above the quick-create target, when there is one, not on it.
        gap = if (selection.quickAnchor != null) 56.dp else 12.dp,
        modifier = Modifier.fillMaxSize(),
    ) {
        CanvasBoardSelectionBar(board, actions, selection, Modifier)
    }
}

/**
 * On a phone the title and actions pills fill the top row, so the bar stays below them even when
 * the host hides the title - unless the host keeps the top clear and the actions are at the foot.
 */
private fun CanvasBoard.barTopClearance(): Dp {
    val pillsOnTop = host.chrome.showTitle || view.compact
    return if (pillsOnTop && !view.actionsInFoot) 64.dp else CANVAS_CHROME_INSET
}

/** The selection's bar: floating on the selection, or riding on a phone's keyboard. */
@Composable
internal fun CanvasBoardSelectionBar(
    board: CanvasBoard,
    actions: CanvasBoardChromeActions,
    selection: CanvasBoardSelection,
    modifier: Modifier,
) {
    val tools = actions.tools
    val controller = board.controller
    val editable = selection.editable
    val hasSelection = board.view.hasSelection
    CanvasSelectionBar(
        state = tools.controlsBar,
        properties = tools.properties,
        hasSelection = hasSelection || selection.notesSelected,
        dispatch = tools.dispatch,
        dispatchProperty = tools.dispatchProperty,
        onBringToFront = { controller.bringSelectionToFront() },
        onSendToBack = { controller.sendSelectionToBack() },
        onDelete = { board.deleteFocused() },
        onDuplicate = { board.duplicateFocused() },
        onEditText = editable?.let { element -> { board.openTextIn(element) } },
        note = selection.noteActions.takeIf { !hasSelection && !selection.notesSelected },
        shapeText = (editable as? Element.Shape)?.let { shape -> CanvasWorkspaceSupport.shapeTextActions(shape, controller) },
        reshape = board.reshapeActions(),
        modifier = modifier.canvasChrome(board.ui.chromeRegions),
    )
}

private fun CanvasBoard.reshapeActions(): ShapeReshapeActions? {
    val shapes = state.elements.filter { it.id in state.selectedIds && CanvasReshape.canReshape(it) }
    if (shapes.isEmpty()) return null
    val types = shapes.map { (it as Element.Shape).shapeType }.distinct()
    return ShapeReshapeActions(current = types.singleOrNull()) { type -> CanvasReshape.apply(controller, type) }
}

/**
 * The arrow being pulled out of a quick-create target: a curve leaving the target square to its
 * side, the shape the arrow it makes will have, with a head at the pointer.
 */
private fun DrawScope.drawQuickCreateArrow(pulled: QuickCreateDrag, color: Color) {
    val from = pulled.from
    val to = pulled.to
    val stroke = QUICK_ARROW_STROKE.toPx()
    val control = CanvasQuickCreate.pullControl(from, to, pulled.direction)
    val curve = Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, to.x, to.y)
    }
    drawPath(curve, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
    // The head follows the curve's last tangent, which runs from the control point.
    val d = if ((to - control).getDistance() >= 1f) to - control else to - from
    val length = d.getDistance()
    if (length < 1f) return
    val unit = d / length
    val normal = Offset(-unit.y, unit.x)
    val back = to - unit * QUICK_ARROW_HEAD_PX
    val spread = normal * (QUICK_ARROW_HEAD_PX * QUICK_ARROW_HEAD_SPREAD)
    drawLine(color, to, back + spread, strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, to, back - spread, strokeWidth = stroke, cap = StrokeCap.Round)
}
