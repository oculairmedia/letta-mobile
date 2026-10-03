package com.letta.mobile.ui.canvas.plugin

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.storage.AssetStore
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.canvas_plugin_move
import com.letta.mobile.sharedui.resources.canvas_plugin_untitled
import com.letta.mobile.ui.canvas.CanvasDocumentRecorder
import com.letta.mobile.ui.canvas.CanvasSelectionChrome
import com.letta.mobile.ui.canvas.InWorldUnits
import com.letta.mobile.ui.canvas.LocalCanvasDocumentRecorder
import com.letta.mobile.ui.canvas.LocalNoteCompositionProbe
import com.letta.mobile.ui.canvas.canvasSelectionStyle
import com.letta.mobile.ui.canvas.chromeInset
import com.letta.mobile.ui.canvas.chromeMargin
import com.letta.mobile.ui.canvas.dragHandle
import com.letta.mobile.ui.canvas.insideChromeMargin
import com.letta.mobile.ui.canvas.onBoard
import com.letta.mobile.ui.canvas.recordingStep
import io.ak1.drawbox.domain.model.ResizeHandle
import io.ak1.drawbox.domain.model.Viewport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * A board's plugin elements and where their pictures come from: the session they are edited
 * through, the elements as the scene holds them, and the snapshot sources (made once per board).
 */
@Immutable
class PluginBoard(
    val session: CanvasSession,
    val elements: List<CanvasPluginElement>,
    val snapshots: PluginSnapshotSources,
)

/**
 * Which element is selected, whether the eraser is held (a press then removes the element), and
 * what a press on an element tells the host ([onSelect], called with the element's id).
 */
@Immutable
class PluginBoardSelection(
    val selectedId: String? = null,
    val eraseMode: Boolean = false,
    val onSelect: (String) -> Unit = {},
)

/**
 * The plugin elements of [session]'s board as of [revision] (anything that changes when the scene
 * does), with their snapshots read from [assets] and fetched through the session's sync transport.
 */
@Composable
fun rememberPluginBoard(session: CanvasSession, revision: CanvasDocument?, assets: AssetStore?): PluginBoard {
    val snapshots = remember(session, assets) { PluginSnapshotSources(assets) { ref -> session.fetchAsset(ref) } }
    return remember(session, revision, snapshots) { PluginBoard(session, PluginElementEdits.elementsOf(session), snapshots) }
}

/**
 * The board's plugin elements (letta-mobile-s416w.4, plan section 4), beside the notes and placed
 * the way they are: in the drawing's world coordinates, moving and scaling with the [viewport].
 *
 * Each element is drawn by the renderer [LocalPluginElementRenderers] has for its type, which in
 * core is always the [PluginFallbackCard]. A press selects it ([PluginBoardSelection.onSelect]); the selected one wears
 * the board's selection chrome and resizes from its handles; a drag on its handle bar moves it.
 * Moves and resizes are written when the gesture ends, as one op that carries only the frame,
 * owned by USER, and recorded as one undoable step in the board's history.
 *
 * Board content, so laid out in world units ([InWorldUnits]); the [viewport] is read in layout and
 * draw only, so a pan or zoom recomposes no element. Each element is its own keyed group with its
 * own guard: an element whose frame is out of range is drawn at a sane one, a snapshot that does
 * not decode shows the icon, and a renderer that fails hands its element back to the fallback card,
 * so one bad element never takes the board with it.
 */
@Composable
fun CanvasPluginLayer(
    board: PluginBoard,
    viewport: Viewport,
    modifier: Modifier = Modifier,
    selection: PluginBoardSelection = PluginBoardSelection(),
) {
    if (board.elements.isEmpty()) return
    // Read by the elements in layout and draw only, through a function whose identity never changes.
    val currentViewport = rememberUpdatedState(viewport)
    val viewportOf: () -> Viewport = remember { { currentViewport.value } }
    // The host's callback is a new object on every recomposition (each frame of a zoom); each element
    // gets a handler made once that calls whatever the host passed last.
    val latestSelect = rememberUpdatedState(selection.onSelect)
    Box(modifier = modifier.fillMaxSize()) {
        board.elements.forEach { element ->
            key(element.id) {
                val select = remember(element.id) { ElementSelect(element.id, latestSelect) }
                PluginElementHost(
                    element = element,
                    board = board,
                    viewport = viewportOf,
                    state = PluginHostState(selected = element.id == selection.selectedId, eraseMode = selection.eraseMode, onSelect = select.select),
                )
            }
        }
    }
}

/** One element's select callback, made once and calling what the host passed last. */
private class ElementSelect(id: String, latest: State<(String) -> Unit>) {
    val select: () -> Unit = { latest.value(id) }
}

/**
 * An element's part in the board's selection; equal when nothing about it changed (its [onSelect]
 * is made once per element), so the element is skipped.
 */
@Immutable
private data class PluginHostState(val selected: Boolean, val eraseMode: Boolean, val onSelect: () -> Unit)

@Composable
private fun PluginElementHost(
    element: CanvasPluginElement,
    board: PluginBoard,
    viewport: () -> Viewport,
    state: PluginHostState,
) {
    LocalNoteCompositionProbe.current?.invoke(element.id)
    val stored = PluginElementFrames.boardFrameOf(element)
    val gesture = remember(element.id) { PluginElementGesture(stored) }
    LaunchedEffect(stored) { gesture.sync(stored) }
    val edits = rememberElementEdits(board.session, element.id)
    // Made once per element: the resize handles restart their gesture whenever their callbacks change.
    val latestStored = rememberUpdatedState(stored)
    val commit: () -> Unit = remember(gesture, edits) { { gesture.end(latestStored.value)?.let(edits::move) } }
    val resize: (ResizeHandle, Offset) -> Unit = remember(gesture) { gesture::resize }
    val view = rememberPluginElementView(element, board)
    val chrome = rememberPluginChrome(element, view, gesture, commit)

    val frame = gesture.frame
    val density = LocalDensity.current
    val size = with(density) { DpSize(frame.width.toDp(), frame.height.toDp()) }
    val topLeft = { Offset(gesture.frame.x, gesture.frame.y) }
    // The element and its selection chrome share one placed, scaled box that carries the chrome's
    // margin on every side, so the outer half of each handle can still be grabbed (see CanvasNotesLayer).
    val insetPx = with(density) { canvasSelectionStyle().chromeInset().toPx() }
    Box(
        modifier = Modifier
            .onBoard(viewport, topLeft, screenInset = insetPx)
            .chromeMargin(viewport, insetPx, width = frame.width, height = frame.height),
    ) {
        Box(
            modifier = Modifier
                .insideChromeMargin(viewport, insetPx)
                // Required, not just a size: the element is never measured again while the zoom changes the margin.
                .requiredSize(size)
                .testTag(CanvasPluginTestTags.element(element.id))
                .pressToSelect(element.id, state.eraseMode) { if (state.eraseMode) edits.remove() else state.onSelect() },
        ) {
            InWorldUnits { LocalPluginElementRenderers.current.rendererFor(view.value)(view.value, chrome) }
        }
        if (state.selected) {
            PluginSelectionChrome(viewport = viewport, contentSize = size, onResize = resize, onResizeEnd = commit)
        }
    }
}

/** What an element's view is built from, and the fault a renderer reported for it. */
private class PluginViewHolder(val value: PluginElementView, val fault: (String) -> Unit)

/** The view of [element]: its snapshot as it arrives, what the board knows of its plugin, its fault. */
@Composable
private fun rememberPluginElementView(element: CanvasPluginElement, board: PluginBoard): PluginViewHolder {
    var faulted by remember(element.id, element.type) { mutableStateOf(false) }
    val snapshot = rememberPluginSnapshot(element.snapshot, board.snapshots)
    val availability = LocalPluginAvailability.current.availabilityOf(element)
    val canvasId = board.session.canvasId.value
    val view = remember(element, snapshot, availability, faulted, canvasId) {
        PluginElementView(element, availability, snapshot, faulted, canvasId)
    }
    return remember(view) { PluginViewHolder(view) { faulted = true } }
}

/** The chrome of [element]'s card: its handle bar's drag, its Open, its way to report a fault. */
@Composable
private fun rememberPluginChrome(
    element: CanvasPluginElement,
    view: PluginViewHolder,
    gesture: PluginElementGesture,
    commit: () -> Unit,
): PluginElementChrome {
    val title = view.value.title ?: stringResource(Res.string.canvas_plugin_untitled)
    val moveLabel = stringResource(Res.string.canvas_plugin_move, title)
    val uriHandler = LocalUriHandler.current
    val latestCommit = rememberUpdatedState(commit)
    val url = PluginLinks.openable(element.fallback.openUrl)
    return remember(element.id, moveLabel, url, uriHandler, view.fault) {
        PluginElementChrome(
            moveHandle = Modifier
                .testTag(CanvasPluginTestTags.handle(element.id))
                .semantics { contentDescription = moveLabel }
                .dragHandle(onDragStart = {}, onDrag = gesture::drag, onDragEnd = { latestCommit.value() }),
            open = url?.let { link -> { PluginLinks.open(uriHandler, link) } },
            fail = { view.fault(it) },
        )
    }
}

/** The writes a person makes to element [id], each recorded as one step in the board's history. */
private class ElementEdits(
    private val session: CanvasSession,
    private val id: String,
    private val scope: CoroutineScope,
    private val recorder: CanvasDocumentRecorder?,
) {
    fun move(frame: CanvasDocumentFrame) {
        scope.launch { recorder.recordingStep { runCatching { PluginElementEdits.move(session, id, frame) }.getOrNull() } }
    }

    fun remove() {
        scope.launch { recorder.recordingStep { runCatching { PluginElementEdits.remove(session, id) }.getOrNull() } }
    }
}

@Composable
private fun rememberElementEdits(session: CanvasSession, id: String): ElementEdits {
    val scope = rememberCoroutineScope()
    val recorder = LocalCanvasDocumentRecorder.current
    return remember(session, id, scope, recorder) { ElementEdits(session, id, scope, recorder) }
}

/**
 * A press on the element belongs to it, never to the drawing beneath: it selects the element (or,
 * with the eraser held, removes it). Seen in the initial pass, so a press on the renderer's own
 * controls selects the element too.
 */
private fun Modifier.pressToSelect(id: String, eraseMode: Boolean, onPress: () -> Unit): Modifier =
    pointerInput(id, eraseMode) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onPress()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
        }
    }.pointerInput(id) { detectTapGestures(onTap = {}) }

/**
 * The selection chrome at the board's current zoom. Its own composable so the zoom it reads
 * recomposes the chrome of the one selected element, never the element.
 */
@Composable
private fun PluginSelectionChrome(
    viewport: () -> Viewport,
    contentSize: DpSize,
    onResize: (ResizeHandle, Offset) -> Unit,
    onResizeEnd: () -> Unit,
) {
    CanvasSelectionChrome(
        style = canvasSelectionStyle(),
        scale = viewport().scale,
        contentWidth = contentSize.width,
        contentHeight = contentSize.height,
        onResize = onResize,
        onResizeEnd = onResizeEnd,
    )
}

/** The links a fallback card opens: the web, and the app's own `meridian:` links. Nothing else. */
internal object PluginLinks {
    private val SCHEMES = setOf("http", "https", "meridian")

    /** [url] when this client opens it, else null. */
    fun openable(url: String?): String? {
        val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
        return trimmed.takeIf { scheme in SCHEMES }
    }

    /** Opens [url] through the platform's link handler; a platform that cannot open it does nothing. */
    fun open(handler: UriHandler, url: String) {
        runCatching { handler.openUri(url) }
    }
}
