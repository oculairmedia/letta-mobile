package com.letta.mobile.ui.canvas

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import io.ak1.drawbox.DrawBox
import io.ak1.drawbox.domain.model.Event
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
    val coroutineScope = rememberCoroutineScope()

    var statusMessage by remember { mutableStateOf("Ready") }
    var initialLoadDone by remember { mutableStateOf(false) }
    var lastExportedJson by remember { mutableStateOf<String?>(null) }
    // The drawing (scene minus our metadata and documents) DrawBox last agreed with the session
    // on. Only a change to *this* re-imports, so a moved note or a saved stroke never reloads
    // the board and throws the camera back.
    var lastDrawing by remember { mutableStateOf<String?>(null) }
    // The active note's formatting controls, drawn at the foot of the board.
    var noteToolbar by remember { mutableStateOf<NoteToolbar?>(null) }
    var isSharingToChat by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }
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
    val boardCenter = Offset(boardSize.width / 2f, boardSize.height / 2f)

    // Ctrl/Cmd + wheel over the board zooms the board, not the window: the host that owns that
    // gesture for UI zoom is told where the board is so it leaves those events alone.
    val wheelZoomRegions = LocalWheelZoomRegions.current
    var boardBounds by remember { mutableStateOf<Rect?>(null) }
    DisposableEffect(wheelZoomRegions) {
        val unregister = wheelZoomRegions?.register { boardBounds }
        onDispose { unregister?.invoke() }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press && expandedNoteId == null) activeNoteId = null
                            }
                        }
                    },
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
                    modifier = Modifier.align(Alignment.TopStart).padding(CHROME_INSET),
                )
            }

            CanvasActionsPill(
                zoom = CanvasZoom(
                    scalePercent = state.viewport.scalePercent,
                    onZoomOut = { controller.zoomBy(1f / ZOOM_STEP, boardCenter) },
                    onZoomIn = { controller.zoomBy(ZOOM_STEP, boardCenter) },
                    onReset = { controller.resetCamera() },
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
                background = state.bgColor,
                onBackground = { color ->
                    controller.setBgColor(color)
                    statusMessage = "Background changed"
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(CHROME_INSET),
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
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(checkpoints) { cp ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        ),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(8.dp),
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
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
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
            if (hasSelection || controlsBarState.showFillTarget || (activeNote != null && expandedNoteId == null)) {
                CanvasSelectionBar(
                    state = controlsBarState,
                    hasSelection = hasSelection,
                    dispatch = dispatch,
                    onBringToFront = { controller.bringSelectionToFront() },
                    onSendToBack = { controller.sendSelectionToBack() },
                    onDelete = { controller.deleteSelected() },
                    note = if (activeNote != null && session != null && !hasSelection) {
                        NoteBarActions(
                            onOpen = { expandedNoteId = activeNote.id },
                            onDelete = {
                                val id = activeNote.id
                                activeNoteId = null
                                coroutineScope.launch { runCatching { session.removeDocument(id) } }
                            },
                        )
                    } else {
                        null
                    },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = if (showTitle) 64.dp else CHROME_INSET),
                )
            }

            // The tool rail down the left, clear of the title pill above and the foot row below.
            // Our own: the drawbox-ui one loads drawables its Android artifact never ships
            // (letta-mobile-r5f3r). See CanvasControlsBar.
            CanvasControlsBar(
                state = controlsBarState,
                dispatch = dispatch,
                onAddNote = session?.let { s ->
                    {
                        val frame = newNoteFrame(state.viewport.screenToWorld(boardCenter))
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
                        val frame = newTextFrame(state.viewport.screenToWorld(boardCenter))
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
                    .padding(start = CHROME_INSET, top = 72.dp, bottom = 64.dp),
            )

            // A note opened large sits over the board, under the foot bar so formatting stays reachable.
            val expanded = documents.firstOrNull { it.id == expandedNoteId }
            if (expanded != null && session != null) {
                CanvasNoteEditorPanel(
                    session = session,
                    document = expanded,
                    onClose = { expandedNoteId = null },
                    onToolbar = { noteToolbar = it },
                )
            }

            // The foot of the board: the active note's formatting bar, centred, above the status line.
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(CHROME_INSET),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
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

private val CHROME_INSET = 12.dp
private const val ZOOM_STEP = 1.25f
private const val WHEEL_ZOOM_STEP = 1.1f
