package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.letta.mobile.data.canvas.CanvasArrowBinding
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasCheckpoint
import com.letta.mobile.data.canvas.CanvasDeletedElement
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.data.canvas.CanvasPresence
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSceneStateGuard
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.CanvasSyncHealth
import com.letta.mobile.data.storage.AssetStore
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.ak1.drawbox.domain.model.State as DrawBoxState

/** What the host hands a canvas workspace, kept together for the parts of the board that need it. */
internal class CanvasWorkspaceHost(
    val controller: DrawBoxController,
    val session: CanvasSession?,
    val sessionRegistry: CanvasSessionRegistry?,
    val initialJson: String?,
    val presenceTransport: CanvasPresenceTransport?,
    val assets: AssetStore?,
    val currentPeerId: String?,
    val callbacks: CanvasWorkspaceCallbacks,
    val chrome: CanvasWorkspaceChromeOptions,
    val cameraRequest: CanvasCameraRequest?,
)

/** What the board hands back to its host. */
internal class CanvasWorkspaceCallbacks(
    val onNavigateBack: (() -> Unit)?,
    val onExportJson: ((String) -> Unit)?,
    val onExportSvg: ((String) -> Unit)?,
    val onShareToChat: ((bytes: ByteArray, mimeType: String) -> Unit)?,
)

/** How the host wants the board's own chrome laid out; see [CanvasWorkspace]. */
internal class CanvasWorkspaceChromeOptions(
    val showTitle: Boolean,
    val headerTrailing: (@Composable () -> Unit)?,
    val layout: CanvasLayout,
    val longPressDrawsSelectionBox: Boolean,
    val chromeTopInset: Dp,
)

/**
 * What the board keeps for as long as it is shown, whatever session it shows: the selection, the
 * caret, the chrome that is open, and the bookkeeping between the drawing and the session.
 */
internal class CanvasBoardUi {
    var statusMessage by mutableStateOf("Ready")
    var initialLoadDone by mutableStateOf(false)

    // The session revision the controller's elements were built from. Documents are derived
    // from the session document and so are current the instant a revision lands, while the
    // elements only catch up when the collector re-imports the scene. Until the two agree, the
    // label reconciler would see every label's shape as missing and delete it.
    var importedRevision by mutableStateOf(0L)

    // True while undo or redo is being applied, so the work it causes is not recorded as a new
    // step: without it, undoing a stroke saves a drawing change that goes straight back on the
    // stack and the button never reaches anything older.
    var applyingHistory by mutableStateOf(false)

    // The elements as they were when the drawing was last saved, so a save that changed nothing
    // about them records no step.
    var lastSavedElements by mutableStateOf<List<Element>?>(null)
    var lastExportedJson by mutableStateOf<String?>(null)

    // The drawing (scene minus our metadata and documents) DrawBox last agreed with the session
    // on. Only a change to *this* re-imports, so a moved note or a saved stroke never reloads
    // the board and throws the camera back.
    var lastDrawing by mutableStateOf<String?>(null)

    // The active note's formatting controls, drawn at the foot of the board.
    var noteToolbar by mutableStateOf<NoteToolbar?>(null)
    var isSharingToChat by mutableStateOf(false)
    var showHistoryDialog by mutableStateOf(false)

    // The background pattern of the session-less preview; a session keeps its own.
    var localPattern by mutableStateOf(CanvasBackgroundPattern())
    var boardSize by mutableStateOf(IntSize.Zero)
    var isAutosaving by mutableStateOf(false)

    // Connector snapping: Alt held (from the last pointer event) turns it off; while a line or
    // arrow is being drawn the nearest anchor to the pointer shows as a ring.
    var altHeld by mutableStateOf(false)
    var drawingConnectorAt by mutableStateOf<Offset?>(null)

    // Notes in the multi-selection (marquee or Shift-click) and the drag they are in the middle of.
    var selectedNoteIds by mutableStateOf<Set<String>>(emptySet())
    var groupOffset by mutableStateOf(Offset.Zero)

    // The note being worked in (toolbar and block handles shown) and the one opened large.
    var activeNoteId by mutableStateOf<String?>(null)
    var expandedNoteId by mutableStateOf<String?>(null)

    // Which of DrawBox's text elements has the caret. DrawBox places text, measures it, wraps it
    // and says when one is to be edited; the editor itself is the host's to render.
    var editingTextId by mutableStateOf<String?>(null)

    // The long-press / right-click menu, while it is open.
    var boardMenu by mutableStateOf<BoardMenuRequest?>(null)
    var boardBounds by mutableStateOf<Rect?>(null)

    // A phone's multi-selection: a long press on a shape picks it as well, and from then on a
    // tap adds or removes one, until the selection is emptied.
    var multiSelecting by mutableStateOf(false)

    // An arrow being pulled out of a quick-create target, and one let go whose menu is open.
    var quickDrag by mutableStateOf<QuickCreateDrag?>(null)
    var quickDrop by mutableStateOf<QuickCreateDrag?>(null)

    // Where images picked from the add menu go.
    var imagesAt by mutableStateOf(Offset.Zero)

    // The note frames each bound connector was last pointed at.
    var lastFrames by mutableStateOf<Map<String, CanvasDocumentFrame>>(emptyMap())

    // Notes being dragged or resized, by id, at their live (uncommitted) frame.
    val liveNoteFrames = mutableStateMapOf<String, CanvasDocumentFrame>()

    // The height each auto-fitted note is SHOWN at (never stored; letta-mobile-bglj6.11). Read only
    // when a group move commits, so it is a plain map and its updates recompose nothing.
    val fittedNoteHeights = HashMap<String, Float>()

    // Whether the press DrawBox is picking for came from a finger, which needs a wider target.
    val fingerRecency = CanvasFingerRecency()

    // Which note or label editor takes the caret next; see CanvasFocusRequest.
    val focusRequest = CanvasFocusRequest()

    // Height of the bars at the foot of the board, which ride up on the keyboard. Kept from their
    // size alone, not their position: watching the position on every layout starved the board's
    // pinch gesture.
    val footHeight = IntArray(1)
    val boardFrame = CanvasBoardFrame()

    // The board's controls are drawn over the board, so their bounds are held here and the pen
    // declines events over them - see CanvasChromeRegions.
    val chromeRegions = CanvasChromeRegions()

    // The stroke under the nib: the board paints it and the pen consumer fills it.
    val penPreview = mutableStateListOf<Element.PathSample>()

    // One recent-colours list for this board, shared by every picker on it.
    val recentColors = RecentColors()
    val boardFocus = FocusRequester()
}

/** What the board keeps for one session, and starts afresh for the next. */
internal class CanvasSessionBoardState(
    /**
     * What the board draws of each projected scene: a scene that breaks the board's rules keeps
     * the last one that drew, and is reported, rather than crashing or blanking it (qygvv.30).
     */
    val sceneGuard: CanvasSceneStateGuard,
) {
    // The board's one undo history, across the drawing (DrawBox's own stack) and the documents
    // (ops on the session). It records the ORDER, which is the one thing neither side can know.
    val history = CanvasHistory()

    // The text elements this board already knows, so a new, empty one is the text tool's and gets
    // the caret.
    var knownTextIds by mutableStateOf<Set<String>?>(null)

    // Images already put in the asset store, and refs being fetched from the host.
    val storedImages = mutableSetOf<String>()
    val fetchingAssets = mutableSetOf<String>()
    var assetRetry by mutableStateOf(0)

    // Whether the open-time fit has been decided.
    var fittedOnOpen by mutableStateOf(false)
    var deletedHistory by mutableStateOf(emptyList<CanvasDeletedElement>())
}

/** The flows the board watches, as states read when they are needed. */
internal class CanvasBoardFlows(
    val state: State<DrawBoxState>,
    val canUndo: State<Boolean>,
    val canRedo: State<Boolean>,
    val historyCanUndo: State<Boolean>,
    val historyCanRedo: State<Boolean>,
    val sessionDoc: State<CanvasDocument?>,
    val sharing: CanvasBoardSharing,
)

/** Whether the board is shared right now, who else is on it, and its saved checkpoints. */
internal class CanvasBoardSharing(
    val syncHealth: State<CanvasSyncHealth?>,
    val presences: State<List<CanvasPresence>>,
    val checkpoints: State<List<CanvasCheckpoint>>,
)

/** The session's documents as of this composition, and as they are now. */
internal class CanvasBoardDocuments(
    val documents: List<CanvasSceneDocument>,
    val live: State<List<CanvasSceneDocument>>,
    val arrowBindings: Map<String, CanvasArrowBinding>,
    val backgroundPattern: CanvasBackgroundPattern,
)

/** How the board is laid out in this composition. */
internal class CanvasBoardView(
    val resolvedLayout: CanvasLayout?,
    val compact: Boolean,
    val actionsInFoot: Boolean,
    val hostChrome: CanvasHostChrome,
    val insets: CanvasBoardInsets,
    val boardCenter: Offset,
    val boardDensity: Float,
    val hasSelection: Boolean,
)

/** What the board's chrome keeps clear of: the system bars, the keyboard and the host's chrome. */
internal class CanvasBoardInsets(
    val chrome: WindowInsets,
    val chromeBottom: Dp,
)

/** Where the board's work runs and is recorded. */
internal class CanvasBoardWork(
    val scope: CoroutineScope,
    val recorder: DocumentRecorderContext,
    val historyActions: HistoryActionContext,
)

/**
 * One composition of the board: what the host passed, what the board keeps, and how it is laid
 * out. The board's actions are extensions on it, so each reads what it needs from one place.
 */
internal class CanvasBoard(
    val host: CanvasWorkspaceHost,
    val ui: CanvasBoardUi,
    val kept: CanvasSessionBoardState,
    val flows: CanvasBoardFlows,
    val docs: CanvasBoardDocuments,
    val view: CanvasBoardView,
    val work: CanvasBoardWork,
) {
    val controller: DrawBoxController = host.controller
    val session: CanvasSession? = host.session
    val scope: CoroutineScope = work.scope
    val documents: List<CanvasSceneDocument> = docs.documents

    /** The drawing as the board last collected it. */
    val state: DrawBoxState by flows.state

    /**
     * Long-lived lambdas - the intent collector, the pointer handlers on the board - are created
     * once; they read the documents through this, never a list captured before a note existed.
     */
    val liveDocuments: List<CanvasSceneDocument> by docs.live
}

/** The board for this composition: what it keeps, the flows it watches, and its layout. */
@Composable
internal fun rememberCanvasBoard(host: CanvasWorkspaceHost): CanvasBoard {
    val ui = remember { CanvasBoardUi() }
    val session = host.session
    val kept = remember(session) { CanvasSessionBoardState(CanvasSceneStateGuard(session?.canvasId?.value)) }
    val flows = collectBoardFlows(host, kept.history)
    val docs = rememberBoardDocuments(session, flows.sessionDoc.value, ui)
    val work = rememberBoardWork(host, ui, kept.history)
    val view = boardView(host.chrome, ui, flows.state.value.selectedIds.isNotEmpty())
    return CanvasBoard(host, ui, kept, flows, docs, view, work)
}

@Composable
private fun collectBoardFlows(host: CanvasWorkspaceHost, history: CanvasHistory): CanvasBoardFlows {
    val controller = host.controller
    val session = host.session
    return CanvasBoardFlows(
        state = controller.state.collectAsState(),
        canUndo = controller.canUndo.collectAsState(),
        canRedo = controller.canRedo.collectAsState(),
        // The buttons answer for the BOARD. Taking their enabled state from DrawBox alone left them
        // greyed out after a note action - there was something to undo, and the only control for
        // it looked unavailable.
        historyCanUndo = history.canUndo.collectAsState(),
        historyCanRedo = history.canRedo.collectAsState(),
        sessionDoc = collectSessionDocument(session),
        sharing = CanvasBoardSharing(
            syncHealth = collectSyncHealth(session),
            presences = collectPresences(host),
            checkpoints = collectCheckpoints(session),
        ),
    )
}

@Composable
private fun collectSessionDocument(session: CanvasSession?): State<CanvasDocument?> {
    return session?.document?.collectAsState() ?: remember { mutableStateOf<CanvasDocument?>(null) }
}

/** Whether this board is shared right now; shown on the board, never assumed. */
@Composable
private fun collectSyncHealth(session: CanvasSession?): State<CanvasSyncHealth?> {
    val health = remember(session) { session?.let(::syncHealthOf) }
    return health?.collectAsState() ?: remember { mutableStateOf<CanvasSyncHealth?>(null) }
}

private fun syncHealthOf(session: CanvasSession): StateFlow<CanvasSyncHealth> {
    return session.syncTransport?.health(session.canvasId)
        ?: MutableStateFlow(CanvasSyncHealth.LocalOnly("This canvas has no sync transport"))
}

@Composable
private fun collectPresences(host: CanvasWorkspaceHost): State<List<CanvasPresence>> {
    val transport = host.presenceTransport
    val session = host.session
    if (transport != null && session != null) {
        return transport.observePresence(session.canvasId).collectAsState(emptyList())
    }
    return remember { mutableStateOf(emptyList()) }
}

@Composable
private fun collectCheckpoints(session: CanvasSession?): State<List<CanvasCheckpoint>> {
    return session?.checkpoints?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
}

@Composable
private fun rememberBoardDocuments(
    session: CanvasSession?,
    sessionDoc: CanvasDocument?,
    ui: CanvasBoardUi,
): CanvasBoardDocuments {
    val documents = remember(sessionDoc) { session?.documents().orEmpty() }
    val live = rememberUpdatedState(documents)
    val pattern = boardBackgroundPattern(session, sessionDoc, ui)
    val arrowBindings = remember(sessionDoc) { session?.arrowBindings().orEmpty() }
    return CanvasBoardDocuments(documents, live, arrowBindings, pattern)
}

/**
 * The background pattern: the session's when there is one (it syncs and reloads with the scene),
 * else this board's own for the session-less preview.
 */
@Composable
private fun boardBackgroundPattern(
    session: CanvasSession?,
    sessionDoc: CanvasDocument?,
    ui: CanvasBoardUi,
): CanvasBackgroundPattern {
    if (session == null) return ui.localPattern
    return remember(sessionDoc) { session.backgroundPattern() ?: CanvasBackgroundPattern() }
}

@Composable
private fun rememberBoardWork(host: CanvasWorkspaceHost, ui: CanvasBoardUi, history: CanvasHistory): CanvasBoardWork {
    val scope = rememberCoroutineScope()
    val controller = host.controller
    val session = host.session
    val recorder = remember(session, history) {
        DocumentRecorderContext(
            session = session,
            history = history,
            isApplyingHistory = { ui.applyingHistory },
        )
    }
    val historyActions = remember(controller, session, history, scope) {
        HistoryActionContext(
            controller = controller,
            session = session,
            history = history,
            scope = scope,
            onApplyingHistory = { ui.applyingHistory = it },
            onStatusMessage = { ui.statusMessage = it },
        )
    }
    return CanvasBoardWork(scope, recorder, historyActions)
}

@Composable
private fun boardView(chrome: CanvasWorkspaceChromeOptions, ui: CanvasBoardUi, hasSelection: Boolean): CanvasBoardView {
    val density = LocalDensity.current
    val boardSize = ui.boardSize
    // Unknown until the board has been measured, so neither tool bar flashes up in the wrong
    // layout for the first frame.
    val boardWidth = with(density) { boardSize.width.toDp() }
    val resolvedLayout = chrome.layout.resolveMeasured(boardSize.width, boardWidth)
    val compact = resolvedLayout == CanvasLayout.COMPACT
    // The phone's chat page keeps the top of the board clear: the actions join the tool bar.
    val hostChrome = LocalCanvasHostChrome.current
    return CanvasBoardView(
        resolvedLayout = resolvedLayout,
        compact = compact,
        actionsInFoot = compact && hostChrome.actionsInFoot,
        hostChrome = hostChrome,
        insets = boardInsets(chrome.chromeTopInset),
        boardCenter = Offset(boardSize.width / 2f, boardSize.height / 2f),
        boardDensity = density.density,
        hasSelection = hasSelection,
    )
}

@Composable
private fun boardInsets(chromeTopInset: Dp): CanvasBoardInsets {
    val systemInsets = WindowInsets.safeDrawing
    val chromeBottomInset = LocalCanvasChromeBottomInset.current
    val chromeInsets = remember(systemInsets, chromeTopInset, chromeBottomInset) {
        systemInsets.union(WindowInsets(top = chromeTopInset, bottom = chromeBottomInset))
    }
    return CanvasBoardInsets(chromeInsets, chromeBottomInset)
}
