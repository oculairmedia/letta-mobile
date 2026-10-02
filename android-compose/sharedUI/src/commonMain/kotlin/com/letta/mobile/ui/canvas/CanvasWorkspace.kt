package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasPresenceTransport
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.ui.theme.LettaDimens
import io.ak1.drawbox.domain.usecase.UseCase
import io.ak1.drawbox.presentation.reducer.Reducer
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController

/** The inset the board's chrome keeps from the board's edges and from each other. */
internal val CANVAS_CHROME_INSET = LettaDimens.Space.md
internal const val WHEEL_ZOOM_STEP = 1.1f

/**
 * Shared Canvas Workspace composable for Meridian: the DrawBox board with block-document notes
 * placed on it as elements, whiteboard-style chrome (title and actions pills, floating tool bar,
 * zoom pill), sample import and JSON/SVG export, and optional persistent [CanvasSession]
 * integration.
 */
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
    /**
     * Where this board keeps its images (and later other large things) instead of only inside the
     * drawing; see [CanvasImageAssets]. Null keeps them inline only, as a session-less preview does.
     */
    assets: com.letta.mobile.data.storage.AssetStore? = null,
    currentPeerId: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    onExportJson: ((String) -> Unit)? = null,
    onExportSvg: ((String) -> Unit)? = null,
    onShareToChat: ((bytes: ByteArray, mimeType: String) -> Unit)? = null,
    /** False when the host already shows the canvas title and a way back, as the desktop side pane does. */
    showTitle: Boolean = true,
    /**
     * The host's own control at the end of the header bar (desktop: the background-tasks
     * button), so the host never has to float one over the board's chrome.
     */
    headerTrailing: (@Composable () -> Unit)? = null,
    /** Phone or desktop chrome; [CanvasLayout.AUTO] decides by the board's width. */
    layout: CanvasLayout = CanvasLayout.AUTO,
    /**
     * A finger's long press on open board drags out a selection box, the desktop's way to pick
     * several at once without a mouse. Off, it opens the board menu, as a phone's does.
     */
    longPressDrawsSelectionBox: Boolean = false,
    /**
     * Host chrome floating over the board's top edge, measured from that edge (the phone's shared
     * chat header over the status bar). The board draws under it; its own chrome keeps below it.
     */
    chromeTopInset: Dp = 0.dp,
    /**
     * Where something outside the board asks the camera to go (the chat's "Show on canvas",
     * letta-mobile-bglj6.13); null when nothing outside steers it.
     */
    cameraRequest: CanvasCameraRequest? = null,
) {
    val host = CanvasWorkspaceHost(
        controller = controller,
        session = session,
        sessionRegistry = sessionRegistry,
        initialJson = initialJson,
        presenceTransport = presenceTransport,
        assets = assets,
        currentPeerId = currentPeerId,
        callbacks = CanvasWorkspaceCallbacks(onNavigateBack, onExportJson, onExportSvg, onShareToChat),
        chrome = CanvasWorkspaceChromeOptions(showTitle, headerTrailing, layout, longPressDrawsSelectionBox, chromeTopInset),
        cameraRequest = cameraRequest,
    )
    CanvasWorkspaceBoard(host, modifier)
}

/**
 * The board itself: what it keeps, the work it does with its session and its camera, and what it
 * draws. The work is launched in the order the board has always relied on.
 */
@Composable
private fun CanvasWorkspaceBoard(host: CanvasWorkspaceHost, modifier: Modifier) {
    val board = rememberCanvasBoard(host)
    CanvasBoardSessionEffects(board)
    CanvasBoardCameraEffects(board)
    CanvasBoardWheelZoomEffect(board)
    CompositionLocalProvider(LocalRecentColors provides board.ui.recentColors) {
        CanvasBoardIntentEffects(board)
        val insert = boardInsertActions(board)
        // Everything composed inside the board records its document edits into the board's
        // history, so an editor writing a note's text produces undo steps of its own rather than
        // leaving undo with nothing between "the note exists" and "it does not".
        val documentRecorder = remember(board.session) {
            CanvasDocumentRecorder { label, block -> board.recordingDocuments(label, block) }
        }
        CompositionLocalProvider(
            LocalCanvasDocumentRecorder provides documentRecorder,
            LocalCanvasFocusRequest provides board.ui.focusRequest,
            LocalCanvasCompact provides board.view.compact,
            LocalCanvasChromeRegions provides board.ui.chromeRegions,
        ) {
            Surface(
                modifier = modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(modifier = Modifier.fillMaxSize().canvasBoardInput(board)) {
                    CanvasBoardLayers(board)
                    CanvasBoardChrome(board, insert)
                }
            }
        }
    }
}
