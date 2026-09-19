package com.letta.mobile.ui.canvas

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import io.ak1.drawbox.DrawBox
import io.ak1.drawbox.domain.model.Event
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.translate
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
    LaunchedEffect(backgroundPattern) {
        // The grid is DrawBox's own (see showGrid below), so the tiled pattern is left unset for
        // it; otherwise both would draw and we would be back to two grids.
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
    var expandedNoteId by remember { mutableStateOf<String?>(null) }

    // Load initial JSON diagram or session document & observe external session updates (Card I2.3 & I3.3)
    LaunchedEffect(session, initialJson) {
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
                initialLoadDone = true

                // Card I2.3: Session observes revision bump -> controller.importPath if JSON changed externally.
                // Conflict: agent replace wins; toast/status.
                session.document.collect { doc ->
                    if (doc != null && doc.revision > lastImportedRev) {
                        lastImportedRev = doc.revision
                        if (doc.sceneJson.isNotBlank() && doc.sceneJson != lastExportedJson) {
                            val cleanJson = CanvasOpProjector.stripMetadataForDrawBox(doc.sceneJson)
                            if (!CanvasOpProjector.drawingsEqual(cleanJson, lastDrawing)) {
                                controller.importPath(cleanJson)
                                lastDrawing = cleanJson
                                statusMessage = "Agent updated canvas (rev ${doc.revision})"
                            }
                        }
                        // Set after any re-import: a bump that changed only documents leaves the
                        // elements already current, so the gate must not stick closed on it.
                        importedRevision = doc.revision
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
                    val hasElements = event.json.contains("\"elements\"")
                    if (!wasAutosaving) {
                        statusMessage = if (hasElements) {
                            "Exported JSON (${event.json.length} chars, verified)"
                        } else {
                            "Warning: Exported JSON missing 'elements' key"
                        }
                    }
                    if (session != null && session.sceneJsonOrEmpty() != event.json) {
                        // Recorded before the write so the session collector, which may run first,
                        // already knows this drawing is DrawBox's own and not an external change.
                        lastDrawing = CanvasOpProjector.stripMetadataForDrawBox(event.json)
                        withContext(Dispatchers.Default) {
                            session.applyLocalScene(event.json)
                        }
                        lastDrawing = CanvasOpProjector.stripMetadataForDrawBox(session.sceneJsonOrEmpty())
                    }
                    onExportJson?.invoke(event.json)
                }
                is Event.SvgExported -> {
                    val hasSvgTag = event.svg.contains("<svg", ignoreCase = true)
                    statusMessage = if (hasSvgTag) {
                        "Exported SVG (${event.svg.length} chars, verified)"
                    } else {
                        "Warning: Exported SVG missing '<svg' tag"
                    }
                    onExportSvg?.invoke(event.svg)
                    if (isSharingToChat && onShareToChat != null) {
                        isSharingToChat = false
                        val bytes = event.svg.encodeToByteArray()
                        if (bytes.size <= com.letta.mobile.data.attachment.AttachmentLimits.Default.maxRawBytesPerImage) {
                            onShareToChat.invoke(bytes, "image/svg+xml")
                            statusMessage = "Shared canvas SVG (${bytes.size} bytes) to chat"
                        } else {
                            statusMessage = "Error: Exported SVG exceeds attachment limit (${bytes.size} bytes)"
                        }
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
    val controlsBarState = CanvasControlsBridge.buildControlsBarState(
        state = state,
        canUndo = canUndo,
        canRedo = canRedo,
    )
    val properties = CanvasControlsBridge.buildProperties(state)
    val dispatchProperty: (CanvasPropertyIntent) -> Unit = { intent ->
        CanvasControlsBridge.dispatchProperty(controller = controller, intent = intent, state = state)
    }
    val boardCenter = Offset(boardSize.width / 2f, boardSize.height / 2f)

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
        val frames = documents.filter { it.id in selectedNoteIds }.mapNotNull { doc ->
            doc.frame?.let { doc.id to it.copy(x = it.x + offset.x, y = it.y + offset.y) }
        }.toMap()
        coroutineScope.launch { runCatching { session.moveDocuments(frames) } }
    }

    // The eraser is a drag, not a click, and DrawBoxController does not surface EraseAt on its
    // intent flow, so notes are taken by watching the eraser's own pointer instead.
    fun eraseNotesAt(world: Offset, radius: Float) {
        if (session == null) return
        val hit = liveDocuments.filter { doc ->
            val f = doc.frame ?: return@filter false
            Rect(f.x - radius, f.y - radius, f.x + f.width + radius, f.y + f.height + radius).contains(world)
        }
        if (hit.isEmpty()) return
        val ids = hit.map { it.id }.toSet()
        if (activeNoteId in ids) activeNoteId = null
        selectedNoteIds = selectedNoteIds - ids
        coroutineScope.launch { ids.forEach { id -> runCatching { session.removeDocument(id) } } }
    }

    // Marquee and move are DrawBox gestures; the notes follow the same intents so a marquee
    // takes in note cards and dragging the selection moves them too, committed on release.
    LaunchedEffect(controller, session) {
        controller.intents.collect { intent ->
            when (intent) {
                is io.ak1.drawbox.domain.model.Intent.CommitMarquee -> {
                    val rect = intent.rect
                    selectedNoteIds = liveDocuments.filter { doc ->
                        val f = doc.frame ?: return@filter false
                        rect.overlaps(Rect(f.x, f.y, f.x + f.width, f.y + f.height))
                    }.map { it.id }.toSet()
                    if (selectedNoteIds.isNotEmpty()) activeNoteId = null
                }
                is io.ak1.drawbox.domain.model.Intent.MoveSelected ->
                    if (selectedNoteIds.isNotEmpty()) groupOffset += intent.delta
                is io.ak1.drawbox.domain.model.Intent.EndTransform -> commitGroupMove()
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
    fun deleteFocused(): Boolean {
        if (selectedNoteIds.isNotEmpty() && session != null) {
            val ids = selectedNoteIds
            selectedNoteIds = emptySet()
            coroutineScope.launch { ids.forEach { id -> runCatching { session.removeDocument(id) } } }
            if (hasSelection) controller.deleteSelected()
            return true
        }
        if (hasSelection) { controller.deleteSelected(); return true }
        val id = activeNoteId ?: return false
        if (session == null || expandedNoteId != null) return false
        activeNoteId = null
        coroutineScope.launch { runCatching { session.removeDocument(id) } }
        return true
    }
    // Duplicate: the drawn selection as offset copies with fresh ids (selected afterwards), or
    // the active note as a new document with the same text, colour and style, 20 units away.
    fun duplicateFocused(): Boolean {
        if (hasSelection) {
            val copies = state.elements.filter { it.id in state.selectedIds }.map { duplicateElement(it) }
            copies.forEach { controller.onIntent(io.ak1.drawbox.domain.model.Intent.AddElement(it)) }
            controller.clearSelection()
            copies.forEach { copy -> controller.onIntent(io.ak1.drawbox.domain.model.Intent.SelectAt(selectionPointOf(copy), 4f)) }
            statusMessage = "Duplicated ${copies.size} element(s)"
            return true
        }
        val note = activeNoteId?.let { id -> documents.firstOrNull { it.id == id } } ?: return false
        if (session == null) return false
        val frame = (note.frame ?: defaultNoteFrame(0)).let { it.copy(x = it.x + DUPLICATE_OFFSET, y = it.y + DUPLICATE_OFFSET) }
        val id = "${note.id.substringBefore('-')}-${Clock.System.now().toEpochMilliseconds()}"
        coroutineScope.launch {
            runCatching { session.setDocument(id, note.json, frame = frame, color = note.color, style = note.style) }
                .onSuccess { activeNoteId = id; statusMessage = "Duplicated note" }
        }
        return true
    }
    fun onBoardKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean = when (canvasKeyAction(event)) {
        CanvasKeyAction.DELETE -> deleteFocused()
        CanvasKeyAction.ESCAPE -> { controller.clearSelection(); activeNoteId = null; expandedNoteId = null; selectedNoteIds = emptySet(); true }
        CanvasKeyAction.UNDO -> { if (canUndo) controller.undo(); true }
        CanvasKeyAction.REDO -> { if (canRedo) controller.redo(); true }
        CanvasKeyAction.DUPLICATE -> duplicateFocused()
        null -> false
    }
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
                .onGloballyPositioned { boardBounds = it.boundsInRoot() }
                .pointerInput(controller) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Scroll) continue
                            val modifiers = event.keyboardModifiers
                            if (!modifiers.isCtrlPressed && !modifiers.isMetaPressed) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val delta = event.changes.fold(0f) { acc, c -> acc + c.scrollDelta.y }
                            if (delta == 0f) continue
                            controller.zoomBy(if (delta > 0f) 1f / WHEEL_ZOOM_STEP else WHEEL_ZOOM_STEP, change.position)
                            event.changes.forEach { it.consume() }
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
                // DrawBox draws a grid of its own, on by default, and the board draws a pattern of
                // its own on top: two grids at two spacings, which is why the background could not
                // be turned off — ours went away and its did not.
                //
                // Now the setting picks exactly one of them. A grid IS DrawBox's grid, drawn by the
                // engine that owns the viewport, so it stays crisp at every zoom. Dots and lines
                // are the board's tiled pattern, which DrawBox has no equivalent for. "None" turns
                // off both, so none means none.
                showGrid = backgroundPattern.kind == CanvasBackgroundPattern.GRID,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .semantics { contentDescription = "Canvas board" }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press && expandedNoteId == null) activeNoteId = null
                                if (event.type == PointerEventType.Press) runCatching { boardFocus.requestFocus() }
                            }
                        }
                    }
                    // A shape holds text: double-tap one to write in it. Placed after DrawBox in
                    // the chain, so DrawBox's own gestures see the pointer first and this only
                    // catches taps nothing else wanted.
                    .pointerInput(session) {
                        detectTapGestures(onDoubleTap = { position ->
                            val current = controller.state.value
                            val world = current.viewport.screenToWorld(position)
                            val shape = CanvasShapeLabels.shapeAt(current.elements, world) ?: return@detectTapGestures
                            val labelId = CanvasShapeLabels.labelIdOf(shape.id)
                            val frame = CanvasShapeLabels.frameFor(shape.bounds())
                            val s = session ?: return@detectTapGestures
                            coroutineScope.launch {
                                val existing = s.documents().firstOrNull { it.id == labelId }
                                if (existing == null) {
                                    runCatching { s.setDocument(labelId, "", frame = frame, color = PLAIN_TEXT_COLOR) }
                                    // Ownership is recorded, not inferred from the name: this is
                                    // what makes the reconciler willing to move and delete it.
                                    runCatching { s.setLabelOwner(labelId, shape.id) }
                                }
                                activeNoteId = labelId
                            }
                        })
                    }
                    // After DrawBox has handled the event (Final pass): track Alt and the pointer
                    // while a connector is drawn, and on release snap the connector just finished.
                    .pointerInput(session) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                altHeld = event.keyboardModifiers.isAltPressed
                                val current = controller.state.value
                                val connectorMode = current.mode == io.ak1.drawbox.domain.model.Mode.LINE ||
                                    current.mode == io.ak1.drawbox.domain.model.Mode.ARROW
                                val position = event.changes.firstOrNull()?.position
                                if (current.mode == io.ak1.drawbox.domain.model.Mode.ERASER &&
                                    position != null && event.changes.any { it.pressed }
                                ) {
                                    eraseNotesAt(
                                        current.viewport.screenToWorld(position),
                                        current.eraserSize / current.viewport.scale.coerceAtLeast(0.01f),
                                    )
                                }
                                when {
                                    !connectorMode -> drawingConnectorAt = null
                                    event.type == PointerEventType.Press || event.type == PointerEventType.Move -> {
                                        if (position != null && event.changes.any { it.pressed }) {
                                            drawingConnectorAt = current.viewport.screenToWorld(position)
                                        }
                                    }
                                    event.type == PointerEventType.Release -> {
                                        drawingConnectorAt = null
                                        if (!altHeld) snapLatestConnector(controller, session, liveDocuments, coroutineScope)
                                    }
                                }
                            }
                        }
                    },
            )

            // The stroke under the nib, until DrawBox owns it.
            CanvasPenPreview(
                samples = penPreview,
                viewport = state.viewport,
                color = state.strokeColor,
                alpha = state.opacity,
                modifier = Modifier.fillMaxSize(),
            )

            // Block documents live on the board as note cards, in world coordinates.
            if (session != null && documents.isNotEmpty()) {
                CanvasNotesLayer(
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
                        if (session != null) {
                            if (activeNoteId == id) activeNoteId = null
                            selectedNoteIds = selectedNoteIds - id
                            coroutineScope.launch { runCatching { session.removeDocument(id) } }
                        }
                    },
                    onPress = { id, shift ->
                        if (shift) {
                            selectedNoteIds = if (id in selectedNoteIds) selectedNoteIds - id else selectedNoteIds + id
                            activeNoteId = null
                        } else if (id !in selectedNoteIds) {
                            // Picking a note replaces the board's selection, exactly as picking a
                            // shape does. Without this the drawn selection stayed put and the note
                            // joined it, so a plain click read as a shift-click.
                            controller.clearSelection()
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
            LaunchedEffect(hasSelection) { if (hasSelection) activeNoteId = null }

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
            DisposableEffect(session, state.mode, penDensity) {
                var stroke: CanvasPenStroke? = null
                // True while the stroke in progress began on a note: the whole stroke belongs to
                // the note, not only the samples that happen to fall inside it.
                var strokeStartedOnDocument = false
                CanvasPenInput.consumer = consumer@{ event ->
                    val current = controller.state.value
                    // The pen reports against the window; the board sits somewhere inside it. Going
                    // straight to screenToWorld skips the offset that Compose's own hit testing
                    // would have applied, and the ink lands away from the nib by however far the
                    // board is inset.
                    val board = boardBounds ?: return@consumer false
                    val inRoot = Offset(event.x * penDensity, event.y * penDensity)
                    val onBoard = Offset(inRoot.x - board.left, inRoot.y - board.top)
                    if (onBoard.x < 0f || onBoard.y < 0f || onBoard.x > board.width || onBoard.y > board.height) {
                        return@consumer false
                    }
                    // Inside the board, but over one of its own controls: the rail, a bar, an
                    // opened note. Those are pressed, not drawn on, and the pen is offered events
                    // by position rather than by hit testing, so it has to decline them itself.
                    if (chromeRegions.contains(inRoot)) return@consumer false
                    val world = current.viewport.screenToWorld(onBoard)
                    if (event.tool == CanvasPenTool.ERASER) {
                        if (event.phase == CanvasPenEvent.Phase.DOWN || event.phase == CanvasPenEvent.Phase.MOVE) {
                            controller.onIntent(io.ak1.drawbox.domain.model.Intent.EraseAt(world, current.eraserSize))
                            eraseNotesAt(world, current.eraserSize / current.viewport.scale.coerceAtLeast(0.01f))
                            return@consumer true
                        }
                        return@consumer false
                    }
                    if (event.tool != CanvasPenTool.DRAW || !current.mode.isFreehandDrawing()) return@consumer false
                    // A note is written in, not drawn on.
                    //
                    // Tested by the note's own frame in world space, not by registered layout
                    // bounds: a card is placed with a graphicsLayer transform, so its layout
                    // bounds say where it was laid out rather than where it is drawn.
                    //
                    // And read from the SESSION rather than the composed list, once per stroke.
                    // The composed list is a frame behind at the moment the nib lands - the pen
                    // arrives off its own poll loop, not on a frame - and a note that is on
                    // screen but not yet in that list gets drawn straight through. Once per
                    // stroke also keeps the projection off the 120Hz sample path.
                    if (event.phase == CanvasPenEvent.Phase.DOWN) {
                        strokeStartedOnDocument = session?.documents()?.any { doc ->
                            doc.frame?.let { f ->
                                world.x >= f.x && world.y >= f.y &&
                                    world.x <= f.x + f.width && world.y <= f.y + f.height
                            } == true
                        } == true
                    }
                    if (strokeStartedOnDocument) return@consumer false
                    when (event.phase) {
                        CanvasPenEvent.Phase.DOWN -> {
                            penPreview.clear()
                            stroke = current.beginPenStroke().also { started ->
                                started.add(world, event.pressure)?.let { penPreview += it }
                            }
                            true
                        }
                        CanvasPenEvent.Phase.MOVE -> stroke?.let { active ->
                            active.add(world, event.pressure)?.let { penPreview += it }
                            true
                        } ?: false
                        CanvasPenEvent.Phase.UP, CanvasPenEvent.Phase.OUT -> {
                            val finished = stroke ?: return@consumer false
                            stroke = null
                            penPreview.clear()
                            finished.finish("pen-${Clock.System.now().toEpochMilliseconds()}")?.let { path ->
                                controller.onIntent(io.ak1.drawbox.domain.model.Intent.AddElement(path))
                            }
                            true
                        }
                        CanvasPenEvent.Phase.IN -> false
                    }
                }
                onDispose { CanvasPenInput.consumer = null }
            }

            // A label lives inside its shape: it is re-framed whenever the shape moves or is
            // resized, and removed with it. Keyed on the element list so a shape dragged by
            // DrawBox carries its text along in the same frame.
            LaunchedEffect(state.elements, liveDocuments, session, importedRevision, initialLoadDone) {
                val s = session ?: return@LaunchedEffect
                val work = CanvasShapeLabels.reconcile(state.elements, liveDocuments, s.labelOwners())
                // Re-framing is safe at any time: it only touches labels whose shape is present.
                if (work.moved.isNotEmpty()) runCatching { s.moveDocuments(work.moved) }
                // Deleting is not. A label is only an orphan once the elements and the documents
                // describe the same revision; before that "no such shape" means "not imported
                // yet", and acting on it destroys the text the shape is holding.
                if (!initialLoadDone || sessionDoc?.revision != importedRevision) return@LaunchedEffect
                work.orphaned.forEach { id ->
                    if (activeNoteId == id) activeNoteId = null
                    runCatching { s.removeDocument(id) }
                    // Released as well as removed, so a document id reused later starts unowned
                    // rather than inheriting a dead shape.
                    runCatching { s.setLabelOwner(id, null) }
                }
            }

            // A bound connector end follows its note: whenever a document's frame changes (a local
            // drag, a peer, the agent), every arrow bound to it is re-pointed through DrawBox, so
            // the move reaches the scene through the normal export rather than a re-import that
            // would throw the camera back.
            var lastFrames by remember { mutableStateOf<Map<String, CanvasDocumentFrame>>(emptyMap()) }
            LaunchedEffect(documents, arrowBindings) {
                val frames = documents.mapNotNull { doc -> doc.frame?.let { doc.id to it } }.toMap()
                frames.forEach { (id, frame) ->
                    if (lastFrames[id] == frame || id !in lastFrames) return@forEach
                    arrowBindings.forEach { (elementId, binding) ->
                        val connector = state.elements.firstOrNull { it.id == elementId } as? io.ak1.drawbox.domain.model.Element.Shape
                            ?: return@forEach
                        CanvasSnapping.follow(connector, binding, id, frame)?.let { geometry ->
                            controller.onIntent(io.ak1.drawbox.domain.model.Intent.SetElementPoints(elementId, geometry.points))
                            if (geometry.bend != connector.bend) {
                                controller.onIntent(io.ak1.drawbox.domain.model.Intent.SetLineBend(elementId, geometry.bend))
                            }
                        }
                    }
                }
                lastFrames = frames
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
                    modifier = Modifier.align(Alignment.TopStart).padding(CHROME_INSET).canvasChrome(chromeRegions),
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
                        val content = CanvasViewportFit.contentBounds(state.elements, documents)
                        controller.resetCamera()
                        if (content != null && boardSize.width > 0 && boardSize.height > 0) {
                            val fit = CanvasViewportFit.fit(content, boardSize.width.toFloat(), boardSize.height.toFloat())
                            // From the reset camera (scale 1, no offset): zooming about the origin
                            // leaves the offset at zero, then one pan places the content.
                            controller.zoomBy(fit.scale, Offset.Zero)
                            controller.panBy(fit.offset)
                            statusMessage = "Fitted to content"
                        }
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
                modifier = Modifier.align(Alignment.TopEnd).padding(CHROME_INSET).canvasChrome(chromeRegions),
            )


            // History dialog
            if (showHistoryDialog && session != null) {
                AlertDialog(
                    onDismissRequest = { showHistoryDialog = false },
                    title = {
                        Text("Revision History", style = MaterialTheme.typography.titleMedium)
                    },
                    text = {
                        if (checkpoints.isEmpty()) {
                            Text("No revision checkpoints recorded yet.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().height(300.dp),
                                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
                            ) {
                                items(checkpoints) { cp ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(LettaDimens.Radius.sm),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = LettaDimens.Alpha.hairline),
                                        ),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.sm),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Revision ${cp.revision}",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                                val desc = if (cp.description.isNotBlank()) cp.description else "Actor: ${cp.actorId}"
                                                Text(
                                                    text = desc,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Button(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        runCatching {
                                                            session.restoreCheckpoint(cp.checkpointId)
                                                        }.onSuccess { restored ->
                                                            // Claim the restored scene as ours first, or the external-update
                                                            // collector sees the revision bump, imports it again and overwrites
                                                            // this status with "Agent updated canvas".
                                                            lastExportedJson = restored.sceneJson
                                                            controller.importPath(CanvasOpProjector.stripMetadataForDrawBox(restored.sceneJson))
                                                            statusMessage = "Restored to revision ${cp.revision}"
                                                            showHistoryDialog = false
                                                        }.onFailure { err ->
                                                            statusMessage = "Restore failed: ${err.message}"
                                                        }
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
                                            ) {
                                                Text("Restore", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = { showHistoryDialog = false }) {
                            Text("Close")
                        }
                    },
                )
            }

            val dispatch: (io.ak1.drawbox.ui.controls.ControlsBarIntent) -> Unit = { intent ->
                CanvasControlsBridge.dispatchIntent(
                    controller = controller,
                    intent = intent,
                    hasSelection = hasSelection,
                )
            }

            // Properties for the selection, or for the closed shape about to be drawn, top-centre.
            // With a note active and nothing drawn selected, the bar is the note's.
            val activeNote = activeNoteId?.let { id -> documents.firstOrNull { it.id == id } }
            val notesSelected = selectedNoteIds.isNotEmpty()
            if (hasSelection || notesSelected || controlsBarState.showFillTarget || (activeNote != null && expandedNoteId == null)) {
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
                    note = if (activeNote != null && session != null && !hasSelection && !notesSelected) {
                        val tint = parseHexColor(activeNote.color)
                        val plain = tint != null && tint.alpha == 0f
                        NoteBarActions(
                            onOpen = { expandedNoteId = activeNote.id },
                            onDelete = {
                                val id = activeNote.id
                                activeNoteId = null
                                coroutineScope.launch { runCatching { session.removeDocument(id) } }
                            },
                            style = activeNote.style,
                            onStyle = { style ->
                                coroutineScope.launch { runCatching { session.restyleDocument(activeNote.id, style) } }
                            },
                            color = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                            onColor = { color ->
                                coroutineScope.launch { runCatching { session.recolorDocument(activeNote.id, color.toHex()) } }
                            },
                            defaultTextColor = if (tint != null && !plain) contrastOn(tint) else MaterialTheme.colorScheme.onSurface,
                            plain = plain,
                        )
                    } else {
                        null
                    },
                    modifier = Modifier.align(Alignment.TopCenter)
                        .padding(top = if (showTitle) 64.dp else CHROME_INSET)
                        .canvasChrome(chromeRegions),
                )
            }

            // The tool rail down the left, clear of the title pill above and the foot row below.
            // Our own: the drawbox-ui one loads drawables its Android artifact never ships
            // (letta-mobile-r5f3r). See CanvasControlsBar.
            CanvasControlsBar(
                state = controlsBarState,
                dispatch = dispatch,
                properties = properties,
                dispatchProperty = dispatchProperty,
                onAddNote = session?.let { s ->
                    {
                        val frame = clearOfExisting(
                            newNoteFrame(state.viewport.screenToWorld(boardCenter)),
                            liveDocuments.mapNotNull { it.frame },
                        )
                        val id = "note-${Clock.System.now().toEpochMilliseconds()}"
                        coroutineScope.launch {
                            runCatching { s.setDocument(id, "", frame = frame, color = NoteColors.first().hex) }
                                .onSuccess {
                                    activeNoteId = id
                                    statusMessage = "Added note"
                                }
                                .onFailure { statusMessage = "Error: could not add note (${it.message})" }
                        }
                    }
                },
                onAddText = session?.let { s ->
                    {
                        val frame = clearOfExisting(
                            newTextFrame(state.viewport.screenToWorld(boardCenter)),
                            liveDocuments.mapNotNull { it.frame },
                        )
                        val id = "text-${Clock.System.now().toEpochMilliseconds()}"
                        coroutineScope.launch {
                            runCatching { s.setDocument(id, "", frame = frame, color = PLAIN_TEXT_COLOR) }
                                .onSuccess {
                                    activeNoteId = id
                                    statusMessage = "Added text"
                                }
                                .onFailure { statusMessage = "Error: could not add text (${it.message})" }
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = CHROME_INSET, top = 72.dp, bottom = 64.dp)
                    .canvasChrome(chromeRegions),
            )

            // A note opened large sits over the board, under the foot bar so formatting stays reachable.
            val expanded = documents.firstOrNull { it.id == expandedNoteId }
            if (expanded != null && session != null) {
                CanvasNoteEditorPanel(
                    session = session,
                    document = expanded,
                    onClose = { expandedNoteId = null },
                    onToolbar = { noteToolbar = it },
                    chromeRegions = chromeRegions,
                )
            }

            // The foot of the board: the active note's formatting bar, centred, above the status line.
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(CHROME_INSET)
                    .canvasChrome(chromeRegions),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                val toolbar = noteToolbar
                if (toolbar != null && (activeNoteId != null || expandedNoteId != null)) {
                    CanvasFormattingBar(toolbar = toolbar)
                }
                CanvasStatusLine(
                    text = "Elements: ${state.elements.size} | $statusMessage",
                    modifier = Modifier.align(Alignment.Start),
                )
            }
        }
    }
    }
}

/**
 * Snaps the connector a release just finished to the nearest anchors within the snap radius:
 * its points move onto them, ends on notes are recorded in the session, and ends on drawn
 * shapes are handed to DrawBox's own binding pass so they follow the shape from then on.
 */
private fun snapLatestConnector(
    controller: DrawBoxController,
    session: CanvasSession?,
    documents: List<com.letta.mobile.data.canvas.CanvasSceneDocument>,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val current = controller.state.value
    val connector = CanvasSnapping.latestConnector(current.elements) ?: return
    val anchors = CanvasSnapping.anchors(current.elements, documents, connector.id)
    val snapped = CanvasSnapping.snap(connector, anchors, current.viewport.scale) ?: return
    if (snapped.points != connector.points) {
        controller.onIntent(io.ak1.drawbox.domain.model.Intent.SetElementPoints(connector.id, snapped.points))
    }
    if (snapped.boundToShape) controller.onIntent(io.ak1.drawbox.domain.model.Intent.FinalizeArrowBindings(connector.id))
    if (session != null && (snapped.binding.start != null || snapped.binding.end != null)) {
        scope.launch { runCatching { session.bindArrow(connector.id, snapped.binding) } }
    }
}

/** [element] moved by [DUPLICATE_OFFSET] with a fresh id, the way whiteboards duplicate in place. */
private fun duplicateElement(element: io.ak1.drawbox.domain.model.Element): io.ak1.drawbox.domain.model.Element {
    val moved = element.translate(Offset(DUPLICATE_OFFSET, DUPLICATE_OFFSET))
    val id = "${element.id}-copy-${Clock.System.now().toEpochMilliseconds()}"
    return when (moved) {
        is io.ak1.drawbox.domain.model.Element.Shape -> moved.copy(id = id, startBinding = null, endBinding = null)
        is io.ak1.drawbox.domain.model.Element.Path -> moved.copy(id = id)
        is io.ak1.drawbox.domain.model.Element.Text -> moved.copy(id = id)
        else -> moved
    }
}

/**
 * A point DrawBox's hit test finds [element] at: on the outline for closed shapes (an unfilled
 * rectangle is only hit on its stroke), the first point of a line, arrow or stroke, the centre
 * for text (hit by its box).
 */
private fun selectionPointOf(element: io.ak1.drawbox.domain.model.Element): Offset = when (element) {
    is io.ak1.drawbox.domain.model.Element.Shape -> when (element.shapeType) {
        io.ak1.drawbox.domain.model.ShapeType.LINE, io.ak1.drawbox.domain.model.ShapeType.ARROW -> element.points.first()
        else -> element.bounds().let { Offset(it.center.x, it.top) }
    }
    is io.ak1.drawbox.domain.model.Element.Path -> element.bounds().let { Offset(it.center.x, it.top) }
    else -> element.bounds().center
}

private const val DUPLICATE_OFFSET = 20f
private val CHROME_INSET = LettaDimens.Space.md
private const val ZOOM_STEP = 1.25f
private const val WHEEL_ZOOM_STEP = 1.1f
