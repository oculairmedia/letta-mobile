package com.letta.mobile.ui.canvas

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasDocumentUndo
import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import io.ak1.drawbox.DrawBox
import io.ak1.drawbox.input.imageDragAndDropTarget
import io.github.vinceglb.filekit.readBytes
import io.ak1.drawbox.domain.model.canHoldText
import io.ak1.drawbox.domain.model.Event
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Shared Canvas Workspace composable for Meridian: the DrawBox board with block-document notes
 * placed on it as elements, whiteboard-style chrome (title and actions pills, floating tool bar,
 * zoom pill), sample import and JSON/SVG export, and optional persistent [CanvasSession]
 * integration.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun CanvasWorkspace(
    modifier: Modifier = Modifier,
    controller: DrawBoxController = remember { DrawBoxController(Reducer(UseCase())) },
    session: CanvasSession? = null,
    /**
     * The registry external tools look the open session up in. Null means agent commands only
     * reach the store, not this session, so a host that wires canvas tools must pass its own.
     */
    sessionRegistry: CanvasSessionRegistry? = null,
    initialJson: String? = null,
    presenceTransport: CanvasPresenceTransport? = null,
    currentPeerId: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    onExportJson: ((String) -> Unit)? = null,
    onExportSvg: ((String) -> Unit)? = null,
    onShareToChat: ((bytes: ByteArray, mimeType: String) -> Unit)? = null,
    /** False when the host already shows the canvas title and a way back, as the desktop side pane does. */
    showTitle: Boolean = true,
    /** Phone or desktop chrome; [CanvasLayout.AUTO] decides by the board's width. */
    layout: CanvasLayout = CanvasLayout.AUTO,
) {
    val state by controller.state.collectAsState()
    val canUndo by controller.canUndo.collectAsState()
    val canRedo by controller.canRedo.collectAsState()
    val sessionDoc by (session?.document?.collectAsState() ?: remember { mutableStateOf(null) })
    val presences by if (presenceTransport != null && session != null) {
        presenceTransport.observePresence(session.canvasId).collectAsState(emptyList())
    } else {
        remember { mutableStateOf(emptyList<CanvasPresence>()) }
    }
    val checkpoints by (session?.checkpoints?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val documents = remember(sessionDoc) { session?.documents().orEmpty() }
    // Long-lived lambdas — the intent collector, the pointer handlers on the board — are created
    // once and keep whatever `documents` held at the time. That is why the eraser and connector
    // snapping worked on every drawn shape and on no note: shapes are read fresh from
    // controller.state, notes came from a list captured before they existed.
    val liveDocuments by rememberUpdatedState(documents)
    val coroutineScope = rememberCoroutineScope()

    var statusMessage by remember { mutableStateOf("Ready") }
    var initialLoadDone by remember { mutableStateOf(false) }
    // The session revision the controller's elements were built from. Documents are derived
    // from `sessionDoc` and so are current the instant a revision lands, while the elements
    // only catch up when the collector below re-imports the scene. Until the two agree, the
    // label reconciler would see every label's shape as missing and delete it.
    var importedRevision by remember { mutableStateOf(0L) }
    // The board's one undo history, across the drawing (DrawBox's own stack) and the documents
    // (ops on the session). It records the ORDER, which is the one thing neither side can know.
    val history = remember(session) { CanvasHistory() }
    // True while undo or redo is being applied, so the work it causes is not recorded as a new
    // step: without it, undoing a stroke saves a drawing change that goes straight back on the
    // stack and the button never reaches anything older.
    var applyingHistory by remember { mutableStateOf(false) }
    // The elements as they were when the drawing was last saved, so a save that changed nothing
    // about them records no step.
    var lastSavedElements by remember {
        mutableStateOf<List<io.ak1.drawbox.domain.model.Element>?>(null)
    }
    var lastExportedJson by remember { mutableStateOf<String?>(null) }
    // The drawing (scene minus our metadata and documents) DrawBox last agreed with the session
    // on. Only a change to *this* re-imports, so a moved note or a saved stroke never reloads
    // the board and throws the camera back.
    var lastDrawing by remember { mutableStateOf<String?>(null) }
    // The active note's formatting controls, drawn at the foot of the board.
    var noteToolbar by remember { mutableStateOf<NoteToolbar?>(null) }
    var isSharingToChat by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    // The background pattern: the session's when there is one (it syncs and reloads with the
    // scene), else this board's own for the session-less preview.
    var localPattern by remember { mutableStateOf(CanvasBackgroundPattern()) }
    val backgroundPattern = if (session != null) {
        remember(sessionDoc) { session.backgroundPattern() ?: CanvasBackgroundPattern() }
    } else {
        localPattern
    }
    // Shape text used to be a separate "label" document laid over the shape. It is the shape's
    // own now; any label still on the board (an older canvas, or a peer on an older app) is folded
    // into its shape's text and removed.
    LaunchedEffect(documents, state.elements, session, initialLoadDone) {
        val s = session ?: return@LaunchedEffect
        if (!initialLoadDone) return@LaunchedEffect
        CanvasWorkspaceSupport.foldLabelsIntoShapes(s, controller)
    }

    // A shape on this board is a box you grab and type into, so a hollow one is picked anywhere
    // inside it, not only on its outline (DrawBox's default, where hollow shapes are frames).
    LaunchedEffect(controller) {
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.SetSelectInsideHollowShapes(true))
    }
    LaunchedEffect(backgroundPattern) {
        // The grid is DrawBox's own (see showGrid below), drawn in the pattern colour; dots and
        // lines are the board's tile. Only one of them is ever set, or there would be two grids.
        val tiled = backgroundPattern.takeIf { it.kind != CanvasBackgroundPattern.GRID }
        controller.setBackgroundPattern(tiled?.painter(), backgroundPattern.tint())
    }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }
    // Connector snapping: Alt held (from the last pointer event) turns it off; while a line or
    // arrow is being drawn the nearest anchor to the pointer shows as a ring.
    var altHeld by remember { mutableStateOf(false) }
    var drawingConnectorAt by remember { mutableStateOf<Offset?>(null) }
    val arrowBindings = remember(sessionDoc) { session?.arrowBindings().orEmpty() }
    // Notes in the multi-selection (marquee or Shift-click) and the drag they are in the middle of.
    var selectedNoteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var groupOffset by remember { mutableStateOf(Offset.Zero) }
    // The note being worked in (toolbar and block handles shown) and the one opened large.
    var activeNoteId by remember { mutableStateOf<String?>(null) }
    // Which of DrawBox's text elements has the caret. DrawBox places text, measures it, wraps it
    // and says when one is to be edited; the editor itself is the host's to render, which is this.
    var editingTextId by remember { mutableStateOf<String?>(null) }
    // Which note or label editor takes the caret next; see CanvasFocusRequest.
    val focusRequest = remember { CanvasFocusRequest() }
    // The long-press / right-click menu, while it is open.
    var boardMenu by remember { mutableStateOf<BoardMenuRequest?>(null) }
    // Notes being dragged or resized, by id, at their live (uncommitted) frame.
    val liveNoteFrames = remember { androidx.compose.runtime.mutableStateMapOf<String, CanvasDocumentFrame>() }
    var expandedNoteId by remember { mutableStateOf<String?>(null) }

    // Load initial JSON diagram or session document & observe external session updates (Card I2.3 & I3.3)
    LaunchedEffect(session, initialJson) {
        // A different board, or the same board replaced: the steps that remain would undo into a
        // board that no longer exists.
        history.clear()
        if (session != null) {
            sessionRegistry?.register(session)
            val syncJob = session.startSync(this)
            try {
                session.load()
                val sessionJson = session.sceneJsonOrEmpty()
                var lastImportedRev = session.document.value?.revision ?: 0L
                // Known even for an empty canvas, or the first note placed on it would read as
                // an external change to the drawing and reload the board.
                lastDrawing = CanvasOpProjector.stripMetadataForDrawBox(sessionJson)
                if (sessionJson.isNotBlank()) {
                    controller.importPath(lastDrawing!!)
                    lastExportedJson = sessionJson
                    statusMessage = "Loaded from session (rev ${session.document.value?.revision ?: 1})"
                }
                importedRevision = lastImportedRev
                delay(100)
                // The baseline is the board AS LOADED, set before any edit is accepted. Left null
                // until the first save, the first drawing change only initialises it and records
                // nothing - so a note done first and a stroke done second undid in the wrong
                // order, taking the older note back before the newer stroke.
                lastSavedElements = controller.state.value.elements
                initialLoadDone = true

                // Card I2.3: Session observes revision bump -> controller.importPath if JSON changed externally.
                // Conflict: agent replace wins; toast/status.
                session.document.collect { doc ->
                    val result = CanvasWorkspaceSupport.evaluateExternalDocSync(
                        ExternalSyncParams(
                            doc = doc,
                            lastImportedRev = lastImportedRev,
                            lastExportedJson = lastExportedJson,
                            lastDrawing = lastDrawing,
                        ),
                    ) ?: return@collect
                    lastImportedRev = result.newImportedRev
                    importedRevision = result.newImportedRev
                    if (result.shouldImport && result.cleanJson != null) {
                        controller.importPath(result.cleanJson)
                        lastDrawing = result.cleanJson
                        result.statusMessage?.let { statusMessage = it }
                    }
                }
            } finally {
                sessionRegistry?.unregister(session.canvasId)
            }
        } else {
            if (!initialJson.isNullOrBlank()) {
                controller.importPath(initialJson)
                statusMessage = "Loaded diagram (${state.elements.size} elements)"
            }
            delay(100)
            lastSavedElements = controller.state.value.elements
            initialLoadDone = true
        }
    }

    var isAutosaving by remember { mutableStateOf(false) }

    // Card I1.6: Autosave debounce
    // Debounce ~500ms on dirty signal (elements change) -> exportJson()
    // The resulting Event.JsonExported persists the updated scene off the main thread.
    LaunchedEffect(state.elements, initialLoadDone, session) {
        if (!initialLoadDone || session == null) return@LaunchedEffect
        delay(500)
        isAutosaving = true
        controller.exportJson()
    }

    // Collect export/error events from DrawBoxController
    LaunchedEffect(controller, session) {
        controller.events.collect { event ->
            when (event) {
                is Event.JsonExported -> {
                    lastExportedJson = event.json
                    val wasAutosaving = isAutosaving
                    isAutosaving = false
                    CanvasWorkspaceSupport.computeJsonExportStatus(event.json, wasAutosaving)?.let {
                        statusMessage = it
                    }
                    if (session != null && session.sceneJsonOrEmpty() != event.json) {
                        lastDrawing = CanvasOpProjector.stripMetadataForDrawBox(event.json)
                        withContext(Dispatchers.Default) {
                            session.applyLocalScene(event.json)
                        }
                        lastDrawing = CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty())
                        val elementsNow = controller.state.value.elements
                        val shouldRecord = CanvasWorkspaceSupport.shouldRecordDrawingStep(
                            elementsBefore = lastSavedElements,
                            elementsNow = elementsNow,
                            isApplyingHistory = applyingHistory,
                        )
                        lastSavedElements = elementsNow
                        if (applyingHistory) {
                            applyingHistory = false
                        } else if (shouldRecord) {
                            history.record(CanvasHistory.Step.Drawing())
                        }
                    }
                    onExportJson?.invoke(event.json)
                }
                is Event.SvgExported -> {
                    statusMessage = CanvasWorkspaceSupport.computeSvgExportStatus(event.svg)
                    onExportSvg?.invoke(event.svg)
                    if (isSharingToChat && onShareToChat != null) {
                        isSharingToChat = false
                        statusMessage = CanvasWorkspaceSupport.handleSvgShareToChat(
                            svg = event.svg,
                            maxBytes = com.letta.mobile.data.attachment.AttachmentLimits.Default.maxRawBytesPerImage,
                            onShare = onShareToChat,
                        )
                    }
                }
                is Event.Error -> {
                    val err = event.message.ifBlank { event.throwable?.message ?: "Unknown" }
                    statusMessage = "Error: $err"
                }
                else -> Unit
            }
        }
    }

    val hasSelection = state.selectedIds.isNotEmpty()
    // The buttons answer for the BOARD. Taking their enabled state from DrawBox alone left them
    // greyed out after a note action - there was something to undo, and the only control for it
    // looked unavailable.
    val historyCanUndo by history.canUndo.collectAsState()
    val historyCanRedo by history.canRedo.collectAsState()
    val controlsBarState = CanvasControlsBridge.buildControlsBarState(
        state = state,
        canUndo = canUndo || historyCanUndo,
        canRedo = canRedo || historyCanRedo,
    )
    val properties = CanvasControlsBridge.buildProperties(state)
    val dispatchProperty: (CanvasPropertyIntent) -> Unit = { intent ->
        CanvasControlsBridge.dispatchProperty(controller = controller, intent = intent, state = state)
    }
    val boardCenter = Offset(boardSize.width / 2f, boardSize.height / 2f)
    // Unknown until the board has been measured, so neither tool bar flashes up in the wrong
    // layout for the first frame.
    val boardWidth = with(LocalDensity.current) { boardSize.width.toDp() }
    val resolvedLayout = layout.resolveMeasured(boardSize.width, boardWidth)
    val compact = resolvedLayout == CanvasLayout.COMPACT

    // Fit everything on the board (elements and notes) with padding; an empty board just goes back
    // to 100% at the origin. [maxScale] lets the open-time fit shrink a board without enlarging it.
    fun fitToContent(maxScale: Float = CanvasViewportFit.MAX_SCALE): Boolean {
        val fit = CanvasViewportFit.fitOrNull(CanvasViewportFit.contentBounds(state.elements, documents), boardSize, maxScale)
        controller.resetCamera()
        if (fit == null) return false
        // From the reset camera (scale 1, no offset): zooming about the origin leaves the offset
        // at zero, then one pan places the content.
        controller.zoomBy(fit.scale, Offset.Zero)
        controller.panBy(fit.offset)
        return true
    }

    // A board drawn on a desktop is mostly off the edge of a phone, which opened on an empty
    // corner of it. On a phone the board opens fitted, once, and never zoomed in past 100%.
    var fittedOnOpen by remember(session) { mutableStateOf(false) }
    LaunchedEffect(initialLoadDone, compact) {
        if (initialLoadDone && compact && !fittedOnOpen) {
            fittedOnOpen = true
            fitToContent(maxScale = 1f)
            // And in the select tool, where a drag moves around the board rather than drawing.
            controller.setMode(io.ak1.drawbox.domain.model.Mode.SELECT)
        }
    }

    val documentRecorderContext = remember(session, history) {
        DocumentRecorderContext(
            session = session,
            history = history,
            isApplyingHistory = { applyingHistory },
        )
    }

    suspend fun recordDocumentChange(
        request: DocumentChangeRequest,
        block: suspend () -> Unit,
    ) = CanvasWorkspaceSupport.recordDocumentChange(documentRecorderContext, request, block)

    /**
     * Runs a document change and records it as one undoable step.
     *
     * The step is a diff of the documents either side of [block] rather than the ops inside it: a
     * single board action can touch several documents through several calls, and what undo owes
     * the person is the state they had.
     */
    suspend fun recordingDocuments(label: String, block: suspend () -> Unit) =
        recordDocumentChange(DocumentChangeRequest(label = label, attachToLastDrawing = false), block)

    /**
     * Records document work that belongs to the drawing change just made.
     *
     * A label goes because its shape went, and the board notices a moment later - the reconciler
     * runs when the elements settle - so the two halves of one action arrive separately. Folding
     * this half into that step is what makes one press give back the shape AND the words it was
     * holding; recorded as a step of its own it would take two, with an empty box in between.
     * When there is no drawing step to fold into, it is recorded on its own rather than lost.
     */
    suspend fun recordingWithLastDrawing(label: String, block: suspend () -> Unit) =
        recordDocumentChange(DocumentChangeRequest(label = label, attachToLastDrawing = true), block)

    val historyActionContext = remember(controller, session, history, coroutineScope) {
        HistoryActionContext(
            controller = controller,
            session = session,
            history = history,
            scope = coroutineScope,
            onApplyingHistory = { applyingHistory = it },
            onStatusMessage = { statusMessage = it },
        )
    }

    fun undoBoard() {
        val drawingUnsaved = lastSavedElements != null && lastSavedElements != state.elements
        CanvasWorkspaceSupport.undoBoard(historyActionContext, drawingUnsaved, canUndo)
    }

    fun redoBoard() {
        CanvasWorkspaceSupport.redoBoard(historyActionContext, canRedo)
    }

    // Ctrl/Cmd + wheel over the board zooms the board, not the window: the host that owns that
    // gesture for UI zoom is told where the board is so it leaves those events alone.
    val wheelZoomRegions = LocalWheelZoomRegions.current
    var boardBounds by remember { mutableStateOf<Rect?>(null) }
    // The board's controls are drawn over the board, so their bounds are held here and the pen
    // declines events over them - see CanvasChromeRegions.
    val chromeRegions = remember { CanvasChromeRegions() }
    // The stroke under the nib. It lives up here because the board paints it and the pen consumer
    // fills it, and those are far apart in this function.
    val penPreview = remember { mutableStateListOf<io.ak1.drawbox.domain.model.Element.PathSample>() }
    DisposableEffect(wheelZoomRegions) {
        val unregister = wheelZoomRegions?.register { boardBounds }
        onDispose { unregister?.invoke() }
    }

    // One recent-colours list for this board, shared by every picker on it.
    val recentColors = remember { RecentColors() }
    CompositionLocalProvider(LocalRecentColors provides recentColors) {
    // A group drag ends: every selected note lands where it was dragged, as one batch op.
    fun commitGroupMove() {
        val offset = groupOffset
        groupOffset = Offset.Zero
        if (session == null || offset == Offset.Zero || selectedNoteIds.isEmpty()) return
        val frames = CanvasWorkspaceSupport.buildMoveFrames(documents, selectedNoteIds, offset)
        coroutineScope.launch {
            recordingDocuments("moving notes") { runCatching { session.moveDocuments(frames) } }
        }
    }

    // The eraser is a drag, not a click, and DrawBoxController does not surface EraseAt on its
    // intent flow, so notes are taken by watching the eraser's own pointer instead.
    fun eraseNotesAt(area: EraserArea) {
        if (session == null) return
        val ids = CanvasWorkspaceSupport.findErasedNoteIds(area, liveDocuments)
        if (ids.isEmpty()) return
        if (activeNoteId in ids) activeNoteId = null
        selectedNoteIds = selectedNoteIds - ids
        coroutineScope.launch {
            recordingDocuments("deleting notes") { ids.forEach { id -> runCatching { session.removeDocument(id) } } }
        }
    }

    // Picks [element] alone, in the select tool. By id, not by a point on it: a point can land
    // on something covering it, such as the connector quick-create ends on the new shape.
    fun selectElement(element: io.ak1.drawbox.domain.model.Element) {
        if (controller.state.value.selectedIds == setOf(element.id)) return
        controller.setMode(io.ak1.drawbox.domain.model.Mode.SELECT)
        controller.selectIds(setOf(element.id))
    }

    // Marquee and move are DrawBox gestures; the notes follow the same intents so a marquee
    // takes in note cards and dragging the selection moves them too, committed on release.
    LaunchedEffect(controller, session) {
        controller.intents.collect { intent ->
            when (intent) {
                is io.ak1.drawbox.domain.model.Intent.CommitMarquee -> {
                    selectedNoteIds = CanvasWorkspaceSupport.findMarqueeNoteIds(intent.rect, liveDocuments)
                    if (selectedNoteIds.isNotEmpty()) activeNoteId = null
                }
                is io.ak1.drawbox.domain.model.Intent.MoveSelected ->
                    if (selectedNoteIds.isNotEmpty()) groupOffset += intent.delta
                is io.ak1.drawbox.domain.model.Intent.EndTransform -> commitGroupMove()
                // The text tool asks for a caret in whatever was tapped: an existing piece of
                // text, or the one DrawBox is about to insert there.
                // Double-tapping asks for a caret in whatever is under the pointer, and DrawBox
                // reports that rather than the board watching for its own double tap: a second
                // tap detector in the chain CONSUMED the first tap, so a single tap never reached
                // DrawBox at all and the text tool placed nothing.
                is io.ak1.drawbox.domain.model.Intent.RequestTextEditAt -> {
                    coroutineScope.launch {
                        CanvasWorkspaceSupport.handleRequestTextEdit(
                            RequestTextEditParams(
                                offset = intent.offset,
                                tolerance = intent.tolerance,
                                elements = controller.state.value.elements,
                                onEditText = { editingTextId = it },
                                // However the text was asked for (a double click, the menu), the
                                // shape is what is selected while it is typed.
                                onEditShapeText = { shape ->
                                    selectElement(shape)
                                    editingTextId = shape.id
                                },
                            ),
                        )
                    }
                }
                is io.ak1.drawbox.domain.model.Intent.ClearSelection,
                is io.ak1.drawbox.domain.model.Intent.SelectAt -> if (groupOffset == Offset.Zero) selectedNoteIds = emptySet()
                else -> Unit
            }
        }
    }

    // Keys on the board: Delete/Backspace removes the drawn selection or the active note, Esc
    // lets both go, Ctrl/Cmd+Z and Ctrl/Cmd+Shift+Z (or +Y) undo and redo the drawing, Ctrl/Cmd+D
    // duplicates. Handled where they bubble to, so a note editor keeps every key it consumes.
    val boardFocus = remember { FocusRequester() }
    fun deleteFocused(): Boolean = CanvasWorkspaceSupport.deleteFocused(
        DeleteFocusedParams(
            selectedNoteIds = selectedNoteIds,
            hasSelection = hasSelection,
            activeNoteId = activeNoteId,
            expandedNoteId = expandedNoteId,
            hasSession = session != null,
            onDeleteSelection = { controller.deleteSelected() },
            onDeleteNotes = { ids ->
                selectedNoteIds = emptySet()
                coroutineScope.launch {
                    recordingDocuments("deleting notes") { ids.forEach { id -> runCatching { session?.removeDocument(id) } } }
                }
            },
            onDeleteActiveNote = { id ->
                activeNoteId = null
                coroutineScope.launch {
                    recordingDocuments("deleting a note") { runCatching { session?.removeDocument(id) } }
                }
            },
        ),
    )

    fun duplicateFocused(): Boolean {
        if (hasSelection) {
            return CanvasWorkspaceSupport.duplicateDrawnSelection(
                controller = controller,
                elements = state.elements,
                selectedIds = state.selectedIds,
                onStatus = { statusMessage = it },
            )
        }
        return CanvasWorkspaceSupport.duplicateActiveNote(
            activeNoteId = activeNoteId,
            documents = documents,
            session = session,
            onCreated = { id, note, frame, s ->
                coroutineScope.launch {
                    recordingDocuments("duplicating a note") {
                        runCatching { s.setDocument(id, note.json, frame = frame, color = note.color, style = note.style) }
                            .onSuccess { activeNoteId = id; statusMessage = "Duplicated note" }
                    }
                }
            },
        )
    }
    // Images: picked (several at once), pasted or dropped, each made ready off the main thread
    // (see prepareCanvasImage) and placed where it was asked for.
    fun placeImages(sources: List<ByteArray>, at: Offset) {
        if (sources.isEmpty()) return
        coroutineScope.launch {
            val images = withContext(Dispatchers.Default) { sources.mapNotNull { prepareCanvasImage(it) } }
            if (images.isEmpty()) {
                statusMessage = "Could not read that image"
                return@launch
            }
            CanvasImages.insert(controller, images, at)
            statusMessage = if (images.size == 1) "Added an image" else "Added ${images.size} images"
        }
    }
    var imagesAt by remember { mutableStateOf(Offset.Zero) }
    val imagePicker = io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher(
        type = io.github.vinceglb.filekit.dialogs.FileKitType.Image,
        mode = io.github.vinceglb.filekit.dialogs.FileKitMode.Multiple(maxItems = CanvasImages.MAX_PICK),
    ) { files ->
        if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
        coroutineScope.launch {
            val bytes = withContext(Dispatchers.Default) {
                files.mapNotNull { file -> runCatching { file.readBytes() }.getOrNull() }
            }
            placeImages(bytes, imagesAt)
        }
    }
    fun pasteImage(at: Offset) {
        io.ak1.drawbox.input.pasteImageFromClipboard { bytes, _ -> placeImages(listOf(bytes), at) }
    }

    fun onBoardKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean = when (canvasKeyAction(event)) {
        CanvasKeyAction.DELETE -> deleteFocused()
        CanvasKeyAction.ESCAPE -> {
            controller.clearSelection()
            activeNoteId = null
            editingTextId = null
            expandedNoteId = null
            selectedNoteIds = emptySet()
            true
        }
        CanvasKeyAction.UNDO -> { undoBoard(); true }
        CanvasKeyAction.REDO -> { redoBoard(); true }
        CanvasKeyAction.DUPLICATE -> duplicateFocused()
        CanvasKeyAction.PASTE -> {
            pasteImage(controller.state.value.viewport.screenToWorld(boardCenter))
            true
        }
        null -> false
    }
    // Puts the caret in [element]: a text element's own editor, or the text of a shape that holds
    // it. A shape stays selected while its text is typed: the menu that floats up is the shape's,
    // because the shape is the thing being worked on.
    fun openTextIn(element: io.ak1.drawbox.domain.model.Element) {
        when {
            element is io.ak1.drawbox.domain.model.Element.Text -> editingTextId = element.id
            element is io.ak1.drawbox.domain.model.Element.Shape && element.canHoldText -> {
                selectElement(element)
                editingTextId = element.id
            }
        }
    }

    fun addNoteAt(world: Offset) {
        val s = session ?: return
        val frame = clearOfExisting(newNoteFrame(world), liveDocuments.mapNotNull { it.frame })
        val id = "note-${Clock.System.now().toEpochMilliseconds()}"
        coroutineScope.launch {
            recordingDocuments("adding a note") {
                runCatching { s.setDocument(id, "", frame = frame, color = NoteColors.first().hex) }
                    .onSuccess {
                        activeNoteId = id
                        focusRequest.documentId = id
                        statusMessage = "Added note"
                    }
                    .onFailure { statusMessage = "Error: could not add note (${it.message})" }
            }
        }
    }

    // Miro's quick create: an empty copy of the selected shape (or note) one gap away, joined to
    // it by an arrow, with the caret in it.
    fun quickCreate(direction: QuickCreateDirection) {
        val current = controller.state.value
        val shape = current.elements.singleOrNull { it.id in current.selectedIds } as? io.ak1.drawbox.domain.model.Element.Shape
        if (shape != null && shape.canHoldText) {
            val next = CanvasQuickCreate.nextShape(shape, direction, current.elements.maxOfOrNull { it.zIndex } ?: 0)
            controller.onIntent(io.ak1.drawbox.domain.model.Intent.AddElement(next))
            val (start, end) = CanvasQuickCreate.connector(shape.bounds(), next.bounds(), direction)
            CanvasQuickCreate.addArrow(controller, start, end)?.let { arrowId ->
                controller.onIntent(io.ak1.drawbox.domain.model.Intent.FinalizeArrowBindings(arrowId))
            }
            openTextIn(next)
            return
        }
        val s = session ?: return
        val note = activeNoteId?.let { id -> liveDocuments.firstOrNull { it.id == id } } ?: return
        val frame = note.frame ?: return
        val nextFrame = CanvasQuickCreate.nextFrame(frame, direction)
        val id = "note-${Clock.System.now().toEpochMilliseconds()}"
        coroutineScope.launch {
            recordingDocuments("adding a note") {
                runCatching { s.setDocument(id, "", frame = nextFrame, color = note.color) }.onSuccess {
                    val (start, end) = CanvasQuickCreate.connector(frame.toRect(), nextFrame.toRect(), direction)
                    if (CanvasQuickCreate.addArrow(controller, start, end) != null) {
                        // The session's documents, which already hold the note just added.
                        CanvasWorkspaceSupport.snapLatestConnector(controller, s, s.documents(), coroutineScope)
                    }
                    activeNoteId = id
                    focusRequest.documentId = id
                }
            }
        }
    }

    val insertActions = BoardInsertActions(
        onAddNote = if (session != null) ::addNoteAt else null,
        onAddText = { world ->
            val current = controller.state.value
            controller.insertText(
                "",
                world,
                current.currentItemFontSize,
                current.currentItemFontFamilyKey,
                current.currentItemTextAlignment,
                current.strokeColor,
            )
        },
        onAddImages = { world ->
            imagesAt = world
            imagePicker.launch()
        },
        onAddShape = { mode, world ->
            val id = CanvasInsert.addShape(controller, mode, world)
            val added = controller.state.value.elements.firstOrNull { it.id == id }
            when {
                added == null -> Unit
                CanvasShapeLabels.canLabel(added) && session != null -> openTextIn(added)
                else -> selectElement(added)
            }
        },
    )

    // A long press or right click: pick what is under it, then open the menu for that, or the
    // add menu on empty board.
    fun openBoardMenu(screen: Offset) {
        val current = controller.state.value
        val world = current.viewport.screenToWorld(screen)
        // DrawBox only selects in the select tool, so the board hit-tests itself and, on an
        // element, moves to the select tool with that element picked - where Miro leaves you too.
        val hit = CanvasWorkspaceSupport.elementAt(current, world, TEXT_HIT_TOLERANCE / current.viewport.scale)
        if (hit != null) selectElement(hit) else controller.clearSelection()
        boardMenu = BoardMenuRequest(
            screen = screen,
            world = world,
            onElement = hit != null,
            canEditText = CanvasWorkspaceSupport.holdsText(hit),
        )
    }

    // Everything composed inside the board records its document edits into the board's history,
    // so an editor writing a note's text produces undo steps of its own rather than leaving undo
    // with nothing between "the note exists" and "it does not".
    val documentRecorder = remember(session) {
        CanvasDocumentRecorder { label, block -> recordingDocuments(label, block) }
    }
    CompositionLocalProvider(
        LocalCanvasDocumentRecorder provides documentRecorder,
        LocalCanvasFocusRequest provides focusRequest,
        LocalCanvasCompact provides compact,
    ) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(boardFocus)
                .focusable()
                .onKeyEvent(::onBoardKey)
                .semantics { contentDescription = "Canvas workspace" }
                .onSizeChanged { boardSize = it }
                // Image files dragged in from the desktop land where they are dropped.
                .imageDragAndDropTarget { drops ->
                    val first = drops.firstOrNull() ?: return@imageDragAndDropTarget
                    placeImages(drops.map { it.bytes }, controller.state.value.viewport.screenToWorld(first.dropPositionScreen))
                }
                .onGloballyPositioned { boardBounds = it.boundsInRoot() }
                .pointerInput(controller) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            CanvasWorkspaceSupport.handleWheelZoom(event, controller)
                        }
                    }
                },
        ) {
            // DrawBox Canvas layer. A press that reaches the drawing (not a note card, not the
            // chrome above it) is a click on the board, which lets the active note go.
            DrawBox(
                state = state,
                onIntent = controller::onIntent,
                // Shapes and notes share one selection look; see CanvasSelectionChrome.
                selectionStyle = canvasSelectionStyle(),
                // Gestures read the controller's state as it is now, not as of the last frame, so
                // anything the board dispatches during a press is already seen by that press.
                liveState = { controller.state.value },
                // The shape whose text is being typed keeps its outline; its text is the editor's.
                hiddenTextElementIds = setOfNotNull(editingShapeText(state.elements, editingTextId)?.id),
                // A grid is DrawBox's: crisp one-pixel lines at every zoom, in the colour and spacing the
                // background menu sets. Dots and lines are the board's tile instead, and "none"
                // turns both off.
                showGrid = backgroundPattern.kind == CanvasBackgroundPattern.GRID,
                gridColor = backgroundPattern.tint(),
                gridSpacing = backgroundPattern.spacing,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .semantics { contentDescription = "Canvas board" }
                    .boardContextGesture(::openBoardMenu)
                    // On a phone one finger on open board in the select tool pans: dragging is how
                    // you move around a board on a phone. (Two-finger pinch is DrawBox's.)
                    .touchNavigation(
                        enabled = compact,
                        canPanFrom = { screen ->
                            CanvasWorkspaceSupport.isOpenBoard(controller.state.value, screen, TEXT_HIT_TOLERANCE)
                        },
                        onPan = { delta -> controller.panBy(delta) },
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press && expandedNoteId == null) activeNoteId = null
                                if (event.type == PointerEventType.Press) editingTextId = null
                                if (event.type == PointerEventType.Press) runCatching { boardFocus.requestFocus() }
                            }
                        }
                    }
                    // After DrawBox has handled the event (Final pass): track Alt and the pointer
                    // while a connector is drawn, and on release snap the connector just finished.
                    .pointerInput(session) {
                        awaitPointerEventScope {
                            // What was on the board when the press began, so the release can tell
                            // a shape that was just drawn from one that was already there.
                            var idsAtPress: Set<String> = emptySet()
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                if (event.type == PointerEventType.Press) idsAtPress = controller.state.value.elements.mapTo(HashSet()) { it.id }
                                if (event.type == PointerEventType.Release) {
                                    CanvasWorkspaceSupport.shapeJustDrawn(controller.state.value, idsAtPress)?.let(::openTextIn)
                                }
                                val result = CanvasWorkspaceSupport.handleFinalPointerPass(
                                    FinalPointerPassParams(
                                        event = event,
                                        current = controller.state.value,
                                        onEraseNotes = { eraseNotesAt(it) },
                                        onSnapLatestConnector = {
                                            CanvasWorkspaceSupport.snapLatestConnector(controller, session, liveDocuments, coroutineScope)
                                        },
                                    ),
                                )
                                altHeld = result.altHeld
                                drawingConnectorAt = result.drawingConnectorAt
                            }
                        }
                    },
            )

            // The text tool places an element and the board puts the caret in it. Read from the
            // ELEMENTS rather than from the insert intent: the intent flow is a buffered broadcast
            // that still drops for a subscriber that falls far enough behind, and the elements are
            // the state itself, so a caret read from them cannot go missing.
            var knownTextIds by remember(session) { mutableStateOf<Set<String>?>(null) }
            LaunchedEffect(state.elements) {
                val (ids, emptyId) = CanvasWorkspaceSupport.detectNewEmptyTextElement(state.elements, knownTextIds)
                knownTextIds = ids
                if (emptyId != null) editingTextId = emptyId
            }

            // The caret, where DrawBox asked for one. Its own editor, placed by its own viewport:
            // the text on the board and the text being typed are then the same thing, measured
            // and wrapped by the same code, which is what a text element is for.
            val editingText = CanvasTextElements.byId(state.elements, editingTextId)
            if (editingText != null) {
                io.ak1.drawbox.text.InlineTextEditor(
                    editingText,
                    state.viewport,
                    editingText.text,
                ) { typed -> controller.updateText(editingText.id, typed) }
            }
            // A shape's text is typed in place, over the shape, the same way.
            val editingShape = editingShapeText(state.elements, editingTextId)
            if (editingShape != null) {
                io.ak1.drawbox.text.InlineShapeTextEditor(
                    editingShape,
                    state.viewport,
                    editingShape.text,
                    onDraftChange = { typed -> controller.updateText(editingShape.id, typed) },
                )
            }

            // The stroke under the nib, until DrawBox owns it.
            CanvasPenPreview(
                samples = penPreview,
                state = state,
                modifier = Modifier.fillMaxSize(),
            )

            // Block documents live on the board as note cards, in world coordinates.
            if (session != null && documents.isNotEmpty()) {
                CanvasNotesLayer(
                    onLiveFrame = { id, frame -> if (frame == null) liveNoteFrames.remove(id) else liveNoteFrames[id] = frame },
                    session = session,
                    documents = documents,
                    viewport = state.viewport,
                    activeNoteId = activeNoteId,
                    expandedNoteId = expandedNoteId,
                    onActivate = { activeNoteId = it },
                    onExpand = { expandedNoteId = it },
                    onToolbar = { noteToolbar = it },
                    selectedIds = selectedNoteIds,
                    groupOffset = groupOffset,
                    // The eraser takes a note the way it takes a stroke: touch it and it is gone.
                    eraseMode = state.mode == io.ak1.drawbox.domain.model.Mode.ERASER,
                    onErase = { id ->
                        if (activeNoteId == id) activeNoteId = null
                        selectedNoteIds = selectedNoteIds - id
                        coroutineScope.launch { recordingDocuments("deleting a note") { runCatching { session.removeDocument(id) } } }
                    },
                    onPress = { id, shift ->
                        if (shift) {
                            selectedNoteIds = if (id in selectedNoteIds) selectedNoteIds - id else selectedNoteIds + id
                            activeNoteId = null
                        } else if (id !in selectedNoteIds) {
                            // Picking a note replaces the board's selection, exactly as picking a
                            // shape does. Without this the drawn selection stayed put and the note
                            // joined it, so a plain click read as a shift-click. A shape's label
                            // picks its shape: the text is the shape's.
                            val owner = CanvasShapeLabels.shapeIdOf(id)?.let { shapeId -> state.elements.firstOrNull { it.id == shapeId } }
                            if (owner != null) selectElement(owner) else controller.clearSelection()
                            selectedNoteIds = emptySet()
                            activeNoteId = id
                        }
                    },
                    onGroupDrag = { delta -> groupOffset += delta },
                    onGroupDragEnd = ::commitGroupMove,
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                )
            }

            // Presence layer (Card I3.5)
            PresenceLayer(
                presences = presences,
                currentPeerId = currentPeerId,
            )

            // Picking a drawing element hands the selection to DrawBox; the note lets go.
            // A shape's label is the exception: its shape being selected is how its text is edited.
    // Keyed on the selection itself, not just whether there is one: duplicating a shape selects
    // the copy without a board press, and the original's label must let go then too.
    LaunchedEffect(state.selectedIds) {
        val labelOf = activeNoteId?.let(CanvasShapeLabels::shapeIdOf)
        if (hasSelection && labelOf !in state.selectedIds) activeNoteId = null
    }

            // The pen draws its own strokes.
            //
            // Everything else the pen does — pressing a button, picking a note, dragging a handle —
            // arrives as ordinary input and needs nothing here. Drawing is the exception: pressure
            // is per sample and no mouse event can carry it, so those events are taken here and
            // built into one Element.Path on lift. Taking them also stops the platform delivering
            // the same stroke a second time without pressure.
            //
            // Flipping the stylus over erases: the eraser nib is a tool in its own right, so it is
            // read from the event rather than asked of the user.
            // Layout coordinates are in pixels; the pen reports in the window's logical units. On
            // a scaled display those differ by the density, and subtracting a pixel-space board
            // offset from a logical-space point put every event outside the board — so the canvas
            // declined them all and the stroke quietly fell back to the pressureless mouse path.
            val penDensity = LocalDensity.current.density
            // Which window's pen this canvas answers, so two open boards cannot take each other's
            // events or clear each other's registration.
            val penTarget = LocalCanvasPenTarget.current
            // The registry belongs to the host that reads the tablet, not to the process.
            val penRegistry = LocalCanvasPenRegistry.current
            val penConsumer = remember(session, state.mode, penDensity, penTarget, penRegistry, boardBounds) {
                CanvasWorkspaceSupport.createPenConsumer(
                    PenConsumerParams(
                        controller = controller,
                        session = session,
                        boardBounds = boardBounds,
                        chromeRegions = chromeRegions,
                        penDensity = penDensity,
                        penPreview = penPreview,
                        onEraseArea = { eraseNotesAt(it) },
                    ),
                )
            }
            DisposableEffect(penConsumer, penTarget, penRegistry) {
                val disposePen = penRegistry.register(penTarget, penConsumer)
                onDispose { disposePen() }
            }

            // A label lives inside its shape: it is re-framed whenever the shape moves or is
            // resized, and removed with it. Keyed on the element list so a shape dragged by
            // DrawBox carries its text along in the same frame.
            LaunchedEffect(state.elements, liveDocuments, session, importedRevision, initialLoadDone) {
                CanvasWorkspaceSupport.reconcileShapeLabels(
                    ReconcileShapeLabelsParams(
                        elements = state.elements,
                        liveDocuments = liveDocuments,
                        session = session,
                        sessionDoc = sessionDoc,
                        importedRevision = importedRevision,
                        initialLoadDone = initialLoadDone,
                        onClearActiveNoteIf = { id -> if (activeNoteId == id) activeNoteId = null },
                        recordDeletion = { block -> recordingWithLastDrawing("deleting a labelled shape") { block() } },
                    ),
                )
            }

            // A bound connector end follows its note: whenever a document's frame changes (a local
            // drag, a peer, the agent), every arrow bound to it is re-pointed through DrawBox, so
            // the move reaches the scene through the normal export rather than a re-import that
            // would throw the camera back.
            var lastFrames by remember { mutableStateOf<Map<String, CanvasDocumentFrame>>(emptyMap()) }
            LaunchedEffect(documents, arrowBindings) {
                lastFrames = CanvasWorkspaceSupport.followConnectors(
                    FollowConnectorsParams(
                        controller = controller,
                        elements = state.elements,
                        arrowBindings = arrowBindings,
                        documents = documents,
                        lastFrames = lastFrames,
                    ),
                )
            }
            val connectorMode = state.mode == io.ak1.drawbox.domain.model.Mode.LINE || state.mode == io.ak1.drawbox.domain.model.Mode.ARROW
            val snapAnchor = drawingConnectorAt?.takeIf { connectorMode && !altHeld }?.let { at ->
                val drawing = CanvasSnapping.latestConnector(state.elements)
                CanvasSnapping.nearest(at, CanvasSnapping.anchors(state.elements, liveDocuments, drawing?.id), state.viewport.scale)
            }
            if (snapAnchor != null) CanvasSnapIndicator(anchor = snapAnchor, viewport = state.viewport)

            if (showTitle) {
                CanvasTitlePill(
                    title = sessionDoc?.title ?: "Canvas",
                    revision = sessionDoc?.revision,
                    onNavigateBack = onNavigateBack?.let { back ->
                        {
                            if (session != null) controller.exportJson()
                            back()
                        }
                    },
                    compact = compact,
                    modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(CHROME_INSET).canvasChrome(chromeRegions),
                )
            }

            CanvasActionsPill(
                zoom = CanvasZoom(
                    scalePercent = state.viewport.scalePercent,
                    onZoomOut = { controller.zoomBy(1f / ZOOM_STEP, boardCenter) },
                    onZoomIn = { controller.zoomBy(ZOOM_STEP, boardCenter) },
                    // Fit everything on the board (elements and notes) with padding; an empty
                    // board just goes back to 100% at the origin.
                    onReset = {
                        if (fitToContent()) statusMessage = "Fitted to content"
                    },
                    onActualSize = { controller.zoomTo(1f, boardCenter) },
                ),
                checkpointCount = if (session != null) checkpoints.size else null,
                onHistory = if (session != null) ({ showHistoryDialog = true }) else null,
                onShare = onShareToChat?.let {
                    {
                        isSharingToChat = true
                        controller.exportSvg()
                    }
                },
                menu = CanvasMenuActions(
                    onImportBuildCycle = {
                        controller.importPath(CanvasSamples.buildCycleJson)
                        statusMessage = "Imported Build Cycle sample"
                    },
                    onImportDailyLoop = {
                        controller.importPath(CanvasSamples.dailyLoopJson)
                        statusMessage = "Imported Daily Loop sample"
                    },
                    onExportJson = { controller.exportJson() },
                    onExportSvg = { controller.exportSvg() },
                    onClear = {
                        controller.reset()
                        statusMessage = "Cleared canvas"
                    },
                ),
                background = CanvasBackgroundActions(
                    color = state.bgColor,
                    onColor = { color ->
                        controller.setBgColor(color)
                        statusMessage = "Background changed"
                    },
                    pattern = backgroundPattern,
                    onPattern = { pattern ->
                        if (session != null) {
                            coroutineScope.launch { runCatching { session.setBackgroundPattern(pattern) } }
                        } else {
                            localPattern = pattern
                        }
                        statusMessage = "Background pattern: ${pattern.kind}"
                    },
                ),
                undo = if (compact) {
                    CanvasUndoActions(
                        canUndo = controlsBarState.canUndo,
                        canRedo = controlsBarState.canRedo,
                        onUndo = ::undoBoard,
                        onRedo = ::redoBoard,
                    )
                } else {
                    null
                },
                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(CHROME_INSET).canvasChrome(chromeRegions),
            )


            CanvasHistoryDialog(
                show = showHistoryDialog && session != null,
                checkpoints = checkpoints,
                onDismiss = { showHistoryDialog = false },
                onRestore = { cp ->
                    val s = session ?: return@CanvasHistoryDialog
                    coroutineScope.launch {
                        runCatching {
                            s.restoreCheckpoint(cp.checkpointId)
                        }.onSuccess { restored ->
                            lastExportedJson = restored.sceneJson
                            controller.importPath(CanvasOpProjector.stripMetadataForDrawBox(restored.sceneJson))
                            history.clear()
                            statusMessage = "Restored to revision ${cp.revision}"
                            showHistoryDialog = false
                        }.onFailure { err ->
                            statusMessage = "Restore failed: ${err.message}"
                        }
                    }
                },
            )

            // Undo and redo are the board's, not the drawing's: a note edit and a stroke are both
            // things the person did, and they undo in the order they were done.
            val dispatch: (io.ak1.drawbox.ui.controls.ControlsBarIntent) -> Unit = dispatch@{ intent ->
                when (intent) {
                    io.ak1.drawbox.ui.controls.ControlsBarIntent.Undo -> {
                        undoBoard()
                        return@dispatch
                    }
                    io.ak1.drawbox.ui.controls.ControlsBarIntent.Redo -> {
                        redoBoard()
                        return@dispatch
                    }
                    else -> Unit
                }
                CanvasControlsBridge.dispatchIntent(
                    controller = controller,
                    intent = intent,
                    hasSelection = hasSelection,
                )
            }

            // Properties for the selection, floating on it the way Miro does; top-centre for the
            // closed shape about to be drawn, when there is nothing to float on. With a note active
            // and nothing drawn selected, the bar is the note's.
            // Where each note is on screen right now: mid-drag, the card's live frame.
            val anchorDocuments = documents.map { d -> liveNoteFrames[d.id]?.let { d.copy(frame = it) } ?: d }
            val activeNote = activeNoteId?.let { id -> anchorDocuments.firstOrNull { it.id == id } }
            val notesSelected = selectedNoteIds.isNotEmpty()
            // The active note's (or shape label's) bar actions, built once for whichever bar shows them.
            val activeNoteActions = if (activeNote != null && session != null) {
                val tint = parseHexColor(activeNote.color)
                val plain = tint != null && tint.alpha == 0f
                NoteBarActions(
                    onOpen = { expandedNoteId = activeNote.id },
                    onDelete = {
                        val id = activeNote.id
                        activeNoteId = null
                        coroutineScope.launch { recordingDocuments("deleting a note") { runCatching { session.removeDocument(id) } } }
                    },
                    style = activeNote.style,
                    onStyle = { style ->
                        coroutineScope.launch {
                            recordingDocuments("restyling a note") {
                                runCatching { session.restyleDocument(activeNote.id, style) }
                            }
                        }
                    },
                    color = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                    onColor = { color ->
                        coroutineScope.launch {
                            recordingDocuments("recolouring a note") {
                                runCatching { session.recolorDocument(activeNote.id, color.toHex()) }
                            }
                        }
                    },
                    defaultTextColor = if (tint != null && !plain) contrastOn(tint) else MaterialTheme.colorScheme.onSurface,
                    plain = plain,
                )
            } else {
                null
            }
            val quickAnchor = CanvasWorkspaceSupport.quickCreateAnchor(
                QuickCreateAnchorParams(
                    state = state,
                    activeNote = activeNote?.takeIf { expandedNoteId == null && !notesSelected },
                    editing = editingTextId != null,
                ),
            )
            if (quickAnchor != null) {
                CanvasQuickCreateTargets(
                    anchor = quickAnchor,
                    onCreate = ::quickCreate,
                    chromeRegions = chromeRegions,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (hasSelection || notesSelected || controlsBarState.showFillTarget || (activeNote != null && expandedNoteId == null)) {
                val editable = state.elements.singleOrNull { it.id in state.selectedIds }
                    ?.takeIf { CanvasWorkspaceSupport.holdsText(it) }
                val topInset = with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() }
                AnchoredToSelection(
                    anchor = CanvasWorkspaceSupport.barAnchor(
                        BarAnchorParams(
                            state = state,
                            documents = anchorDocuments,
                            selectedNoteIds = selectedNoteIds,
                            activeNote = activeNote,
                            groupOffset = groupOffset,
                        ),
                    ),
                    // On a phone the title and actions pills fill the top row, so the bar stays
                    // below them even when the host hides the title.
                    topClearance = topInset + if (showTitle || compact) 64.dp else CHROME_INSET,
                    startClearance = resolvedLayout.railClearance(),
                    // Above the quick-create target, when there is one, not on it.
                    gap = if (quickAnchor != null) 56.dp else 12.dp,
                    modifier = Modifier.fillMaxSize(),
                ) {
                CanvasSelectionBar(
                    state = controlsBarState,
                    properties = properties,
                    hasSelection = hasSelection || notesSelected,
                    dispatch = dispatch,
                    dispatchProperty = dispatchProperty,
                    onBringToFront = { controller.bringSelectionToFront() },
                    onSendToBack = { controller.sendSelectionToBack() },
                    onDelete = { deleteFocused() },
                    onDuplicate = { duplicateFocused() },
                    onEditText = editable?.let { element -> { openTextIn(element) } },
                    note = activeNoteActions.takeIf { !hasSelection && !notesSelected },
                    shapeText = (editable as? io.ak1.drawbox.domain.model.Element.Shape)?.let { shape ->
                        CanvasWorkspaceSupport.shapeTextActions(shape, controller)
                    },
                    modifier = Modifier.canvasChrome(chromeRegions),
                )
                }
            }

            // The tool rail down the left, clear of the title pill above and the foot row below.
            // Our own: the drawbox-ui one loads drawables its Android artifact never ships
            // (letta-mobile-r5f3r). See CanvasControlsBar.
            val onAddNote: (() -> Unit)? = if (session != null) ({ addNoteAt(state.viewport.screenToWorld(boardCenter)) }) else null
            if (resolvedLayout == CanvasLayout.EXPANDED) {
                CanvasControlsBar(
                    state = controlsBarState,
                    dispatch = dispatch,
                    properties = properties,
                    dispatchProperty = dispatchProperty,
                    onAddNote = onAddNote,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(start = CHROME_INSET, top = 72.dp, bottom = 64.dp)
                        .canvasChrome(chromeRegions),
                )
            }

            // A note opened large sits over the board, under the foot bar so formatting stays reachable.
            val expanded = documents.firstOrNull { it.id == expandedNoteId }
            if (expanded != null && session != null) {
                CanvasNoteEditorPanel(
                    session = session,
                    document = expanded,
                    onClose = { expandedNoteId = null },
                    onToolbar = { noteToolbar = it },
                    chromeRegions = chromeRegions,
                    compact = compact,
                )
            }

            // The foot of the board: the active note's formatting bar, centred, above the status line
            // on a desktop and above the tool bar on a phone. Inset from the system bars and the
            // keyboard, so on a phone the formatting bar rides up with the keyboard.
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(CHROME_INSET)
                    .canvasChrome(chromeRegions),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                val toolbar = noteToolbar
                if (toolbar != null && (activeNoteId != null || expandedNoteId != null)) {
                    CanvasFormattingBar(toolbar = toolbar)
                }
                when (resolvedLayout) {
                    CanvasLayout.COMPACT -> if (expanded == null) {
                        CanvasCompactToolbar(
                            state = controlsBarState,
                            properties = properties,
                            actions = CompactToolbarActions(
                                dispatch = dispatch,
                                dispatchProperty = dispatchProperty,
                                insert = insertActions,
                                addAt = { controller.state.value.viewport.screenToWorld(boardCenter) },
                            ),
                        )
                    }
                    else -> CanvasStatusLine(
                        text = "Elements: ${state.elements.size} | $statusMessage",
                        modifier = Modifier.align(Alignment.Start),
                    )
                }
            }

            boardMenu?.let { request ->
                CanvasBoardMenu(
                    request = request,
                    insert = insertActions,
                    element = BoardElementActions(
                        onEditText = {
                            val current = controller.state.value
                            current.elements.singleOrNull { it.id in current.selectedIds }?.let(::openTextIn)
                        },
                        onDuplicate = { duplicateFocused() },
                        onBringToFront = { controller.bringSelectionToFront() },
                        onSendToBack = { controller.sendSelectionToBack() },
                        onDelete = { deleteFocused() },
                    ),
                    onDismiss = { boardMenu = null },
                )
            }
        }
    }
    }
    }
}

private const val INSERT_TEXT_TIMEOUT_MS = 2000L
private val CHROME_INSET = LettaDimens.Space.md
private const val ZOOM_STEP = 1.25f
/** How near, in screen pixels at 100%, a press has to be to an element to pick it. */
private const val TEXT_HIT_TOLERANCE = 8f
internal const val WHEEL_ZOOM_STEP = 1.1f

/** The shape whose text is being typed, when [editingId] names one that holds text. */
private fun editingShapeText(
    elements: List<io.ak1.drawbox.domain.model.Element>,
    editingId: String?,
): io.ak1.drawbox.domain.model.Element.Shape? =
    editingId?.let { id -> elements.firstOrNull { it.id == id } as? io.ak1.drawbox.domain.model.Element.Shape }
        ?.takeIf { it.canHoldText }
