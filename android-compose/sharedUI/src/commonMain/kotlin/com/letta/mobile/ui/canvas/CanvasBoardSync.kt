package com.letta.mobile.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.ui.canvas.lifetime.CanvasWorkspaceLoadState
import com.letta.mobile.ui.canvas.lifetime.CanvasWorkspaceSessionLifecycle
import com.letta.mobile.util.Telemetry
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Event
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.PayLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.time.TimeSource

/** How long before asking the host again for an asset it did not have yet. */
private const val ASSET_RETRY_MS = 10_000L

/** How long the board waits after loading before it takes its undo baseline. */
private const val LOAD_SETTLE_MS = 100L

/** How long the board waits for changes to settle before it saves them. */
private const val AUTOSAVE_DEBOUNCE_MS = 500L

/**
 * The board's work with its session and its drawing: folding old labels, the background, the
 * load and live document import, the autosave, the images and the export events. Launched in this
 * order, which is the order the board has always relied on.
 */
@Composable
internal fun CanvasBoardSessionEffects(board: CanvasBoard) {
    val session = board.session
    val state = board.state
    val ui = board.ui
    // Shape text used to be a separate "label" document laid over the shape. It is the shape's
    // own now; any label still on the board (an older canvas, or a peer on an older app) is folded
    // into its shape's text and removed.
    LaunchedEffect(board.documents, state.elements, session, ui.initialLoadDone) { board.foldLegacyLabels() }
    // A shape on this board is a box you grab and type into, so a hollow one is picked anywhere
    // inside it, not only on its outline (DrawBox's default, where hollow shapes are frames).
    LaunchedEffect(board.controller) { board.controller.onIntent(Intent.SetSelectInsideHollowShapes(true)) }
    val pattern = board.docs.backgroundPattern
    LaunchedEffect(pattern) { board.applyBackgroundPattern(pattern) }
    // Load the initial JSON diagram or the session document and observe external session updates.
    LaunchedEffect(session, board.host.initialJson) { board.loadBoard() }
    // Autosave and viewer handoff use the same acknowledged drawing save. Both elements and
    // background colour are drawing edits; a tool or camera change is not.
    LaunchedEffect(state.elements, state.bgColor, ui.initialLoadDone, session) { board.autosave() }
    // Images placed on this board, and those of boards made before it kept images as assets, are
    // moved into the asset store: their bytes stored once under their hash.
    LaunchedEffect(state.elements, board.host.assets, ui.initialLoadDone) { board.adoptImages() }
    // An image another app put on the board arrives as its ref and a preview; its full bytes are
    // fetched from the host in the background and swapped in, outside undo, as they land.
    LaunchedEffect(state.elements, board.host.assets, session, ui.initialLoadDone, board.kept.assetRetry) {
        board.fetchMissingAssets()
    }
    LaunchedEffect(board.controller, session) { board.collectBoardEvents() }
}

/** Keeps loading, registration and remote ops alive independently of the selected viewer. */
@Composable
internal fun CanvasWorkspaceSessionLifetime(
    session: CanvasSession?,
    sessionRegistry: CanvasSessionRegistry?,
): CanvasWorkspaceSessionLifecycle {
    val loadState = remember(session) {
        mutableStateOf(initialWorkspaceLoadState(session))
    }
    val retryGeneration = remember(session) { mutableIntStateOf(0) }
    val retry = remember(loadState, retryGeneration) {
        {
            loadState.value = CanvasWorkspaceLoadState.Loading
            retryGeneration.intValue++
            Unit
        }
    }
    LaunchedEffect(session, sessionRegistry, retryGeneration.intValue) {
        val activeSession = session ?: return@LaunchedEffect
        var syncJob: Job? = null
        var registered = false
        try {
            val failure = runCatching {
                if (activeSession.document.value == null) {
                    loadState.value = CanvasWorkspaceLoadState.Loading
                    activeSession.loadIfMissing()
                }
                if (sessionRegistry != null && sessionRegistry.get(activeSession.canvasId) !== activeSession) {
                    sessionRegistry.register(activeSession)
                    registered = true
                }
                syncJob = activeSession.startSync(this)
                loadState.value = CanvasWorkspaceLoadState.Ready
                awaitCancellation()
            }.exceptionOrNull()
            loadState.value = activeSession.completedLoadState(failure)
        } finally {
            withContext(NonCancellable) {
                syncJob?.cancelAndJoin()
                if (registered && sessionRegistry?.get(activeSession.canvasId) === activeSession) {
                    sessionRegistry.unregister(activeSession.canvasId)
                }
            }
        }
    }
    return CanvasWorkspaceSessionLifecycle(loadState, retry)
}

private fun initialWorkspaceLoadState(session: CanvasSession?): CanvasWorkspaceLoadState =
    if (session == null || session.document.value != null) CanvasWorkspaceLoadState.Ready
    else CanvasWorkspaceLoadState.Loading

private fun CanvasSession.completedLoadState(failure: Throwable?): CanvasWorkspaceLoadState = when (failure) {
    null -> CanvasWorkspaceLoadState.Ready
    is CancellationException -> throw failure
    is Exception -> {
        Telemetry.error("CanvasWorkspace", "session.load.failed", failure, "canvasId" to canvasId.value)
        CanvasWorkspaceLoadState.Failed
    }
    else -> throw failure
}

private suspend fun CanvasSession.loadIfMissing() {
    if (document.value == null) load()
}

private suspend fun CanvasBoard.foldLegacyLabels() {
    val s = session ?: return
    if (!ui.initialLoadDone) return
    CanvasWorkspaceSupport.foldLabelsIntoShapes(s, controller)
}

/**
 * The grid is DrawBox's own, drawn in the pattern colour; dots and lines are the board's tile.
 * Only one of them is ever set, or there would be two grids.
 */
private fun CanvasBoard.applyBackgroundPattern(pattern: CanvasBackgroundPattern) {
    val tiled = pattern.takeIf { it.kind != CanvasBackgroundPattern.GRID }
    controller.setBackgroundPattern(tiled?.painter(), pattern.tint())
}

/**
 * Opens the board: the session's scene, kept in step with the session from then on, or the
 * host's initial JSON for a session-less preview. A different board, or the same board
 * replaced, starts with an empty undo history: the steps that remain would undo into a board
 * that no longer exists.
 */
private suspend fun CanvasBoard.loadBoard() {
    kept.history.clear()
    val s = session
    if (s == null) {
        loadPreview()
        return
    }
    openSession(s)
}

private suspend fun CanvasBoard.loadPreview() {
    val json = host.initialJson
    if (!json.isNullOrBlank()) {
        controller.importPath(json)
        ui.statusMessage = "Loaded diagram (${state.elements.size} elements)"
    }
    delay(LOAD_SETTLE_MS)
    ui.lastSavedElements = controller.state.value.elements
    ui.initialLoadDone = true
}

private suspend fun CanvasBoard.openSession(s: CanvasSession) {
    val openStarted = TimeSource.Monotonic.markNow()
    val sessionJson = s.sceneJsonOrEmpty()
    var lastImportedRev = s.document.value?.revision ?: 0L
    // Parsing the scene is the heaviest thing opening a board does, and a board with pictures on
    // it is megabytes of JSON: it is parsed off the main thread, and only the parsed drawing is
    // handed to the controller on it.
    val opened = withContext(Dispatchers.Default) { parseOpenedScene(sessionJson) }
    beforeSessionImport(s)
    // Known even for an empty canvas, or the first note placed on it would read as an external
    // change to the drawing and reload the board.
    ui.lastDrawing = opened.drawing
    ui.lastSyncedScene = sessionJson
    if (sessionJson.isNotBlank()) {
        importOpenedScene(opened, s, openStarted)
    }
    ui.importedRevision = lastImportedRev
    // Take the actual imported DrawBox snapshot before accepting edits. Returning to the board
    // never reloads the store over a newer in-memory session.
    acceptDrawingBaseline()
    ui.initialLoadDone = true
    // Each revision is compared and parsed off the main thread too. While one is being parsed the
    // collector is suspended, and the document flow keeps only the newest revision, so a catch-up
    // burst of many revisions lands as one import. An agent's replace wins.
    s.document.collect { doc -> lastImportedRev = syncExternalRevision(doc, lastImportedRev) }
}

/** The scene a session opened with: as stored, stripped for DrawBox (kept parsed), and decoded. */
private class OpenedScene(val json: String, val drawing: JsonObject?, val parsed: PayLoad?)

/**
 * Elements DrawBox cannot read are dropped (and reported) first: one of them would otherwise fail
 * the whole scene and open an empty board. A scene that breaks the board's state rules is
 * reported too (see the scene guard). The scene is parsed once, and stripped, checked and decoded
 * from that tree.
 */
private fun CanvasBoard.parseOpenedScene(sessionJson: String): OpenedScene {
    val root = CanvasOpProjector.parseSceneOrNull(sessionJson)
    val parsed = when {
        sessionJson.isBlank() -> null
        root != null -> CanvasImageAssets.parse(kept.sceneGuard.drawable(root, sessionJson.length), host.assets)
        else -> CanvasImageAssets.parse(kept.sceneGuard.drawable(sessionJson), host.assets)
    }
    return OpenedScene(sessionJson, root?.let(CanvasOpProjector::stripMetadataForDrawBox), parsed)
}

private fun CanvasBoard.importOpenedScene(
    scene: OpenedScene,
    s: CanvasSession,
    openStarted: TimeSource.Monotonic.ValueTimeMark,
) {
    // A scene that does not parse still goes through the text path, which reports it.
    val parsed = scene.parsed
    if (parsed != null) controller.importPath(parsed) else controller.importPath(CanvasOpProjector.stripMetadataForDrawBox(scene.json))
    Telemetry.event(
        "CanvasPerformance", "open.loaded",
        "canvasId" to s.canvasId.value,
        "sceneChars" to scene.json.length,
        "elements" to controller.state.value.elements.size,
        durationMs = openStarted.elapsedNow().inWholeMilliseconds,
    )
    ui.lastExportedJson = scene.json
    ui.statusMessage = "Loaded from session (rev ${s.document.value?.revision ?: 1})"
}

/** Brings in a revision the session got from elsewhere; answers the revision now imported. */
private suspend fun CanvasBoard.syncExternalRevision(doc: CanvasDocument?, lastImportedRev: Long): Long =
    ui.drawingSaveMutex.withLock {
        if (doc == null || doc.revision <= lastImportedRev) lastImportedRev else importLatestRevision(lastImportedRev)
    }

private suspend fun CanvasBoard.importLatestRevision(lastImportedRev: Long): Long {
    // Preserve pending local edits before importing a remote drawing. While parsing off the
    // UI thread, another edit can arrive; retry with that edit saved before replacing state.
    while (true) {
        if (!flushDrawingLocked()) return lastImportedRev
        val drawing = controller.state.value
        val latest = session?.document?.value
        val params = ExternalSyncParams(latest, lastImportedRev, ui.lastExportedJson, ui.lastDrawing, ui.lastSyncedScene)
        val evaluated = withContext(Dispatchers.Default) { evaluateExternal(params) } ?: return lastImportedRev
        if (!drawing.matchesDrawing(controller.state.value)) continue
        val (result, parsedExternal) = evaluated
        ui.importedRevision = result.newImportedRev
        ui.lastSyncedScene = latest?.sceneJson
        if (result.shouldImport) {
            importExternalScene(ExternalScene(latest, result, parsedExternal), TimeSource.Monotonic.markNow())
            acceptDrawingBaseline()
            result.statusMessage?.let { ui.statusMessage = it }
        }
        return result.newImportedRev
    }
}

/**
 * The scene is parsed once, in the evaluation; the guard checks and strips that tree and DrawBox
 * decodes what it hands back. Only a scene that does not parse at all goes the text way, which
 * reports it (and draws the last scene that did).
 */
private fun CanvasBoard.evaluateExternal(params: ExternalSyncParams): Pair<ExternalSyncResult, PayLoad?>? {
    val evaluated = CanvasWorkspaceSupport.evaluateExternalDocSync(params) ?: return null
    if (!evaluated.shouldImport) return evaluated to null
    val sceneJson = params.doc?.sceneJson.orEmpty()
    val drawable = evaluated.scene?.let { kept.sceneGuard.drawable(it, sceneJson.length) }
    val payload = if (drawable != null) {
        CanvasImageAssets.parse(drawable, host.assets)
    } else {
        CanvasImageAssets.parse(kept.sceneGuard.drawable(sceneJson), host.assets)
    }
    return evaluated to payload
}

/** A revision from elsewhere: what its evaluation found, and the drawing decoded. */
private class ExternalScene(val doc: CanvasDocument?, val evaluated: ExternalSyncResult, val parsed: PayLoad?)

private fun CanvasBoard.importExternalScene(scene: ExternalScene, importStarted: TimeSource.Monotonic.ValueTimeMark) {
    // A change from another app must not move this one's camera or tool.
    val parsed = scene.parsed
    if (parsed != null) {
        controller.importExternal(parsed)
    } else {
        controller.importExternal(CanvasOpProjector.stripMetadataForDrawBox(scene.doc?.sceneJson.orEmpty()))
    }
    Telemetry.event(
        "CanvasPerformance", "sync.imported",
        "revision" to scene.doc?.revision,
        "sceneChars" to scene.doc?.sceneJson?.length,
        "changed" to scene.evaluated.changedElements,
        "changedSample" to scene.evaluated.changedSample,
        durationMs = importStarted.elapsedNow().inWholeMilliseconds,
        level = Telemetry.Level.DEBUG,
    )
    // Nor put a caret in text placed there: it is known before the caret effect sees it, or text
    // placed on a desktop opened a phone's keyboard.
    kept.knownTextIds = CanvasTextElements.ids(controller.state.value.elements)
    ui.lastDrawing = scene.evaluated.drawing
}

/** Debounces drawing edits; a viewer handoff flushes the same save without this delay. */
private suspend fun CanvasBoard.autosave() {
    if (!ui.initialLoadDone || session == null) return
    delay(AUTOSAVE_DEBOUNCE_MS)
    flushDrawing()
}

/**
 * The image itself is given its ref (and a preview) only once drawings stop carrying image bytes
 * (DrawingSerializer.inlineImageBytes off), when every app reads refs. Before then,
 * rewriting it fought any older app on the board: that app's drawing drops fields it does not
 * know, so its next save sent every image back without them, this board adopted them again, and
 * the two traded the same images forever. So until then the store is only filled, which needs
 * no one else's agreement. Each image is stored once, not on every change to the board.
 */
private suspend fun CanvasBoard.adoptImages() {
    val store = host.assets ?: return
    if (!ui.initialLoadDone) return
    val writeRefs = !DrawingSerializer.inlineImageBytes
    val pending = state.elements.filterIsInstance<Element.Image>()
        .filter { it.assetRef == null && it.bytes.isNotEmpty() }
        .filter { writeRefs || storedKey(it) !in kept.storedImages }
    if (pending.isEmpty()) return
    val adopted = withContext(Dispatchers.Default) { pending.map { CanvasImageAssets.adopt(it, store) } }
    if (!writeRefs) {
        pending.forEach { kept.storedImages += storedKey(it) }
        return
    }
    applyAdoptedImages(adopted)
}

private fun CanvasBoard.applyAdoptedImages(adopted: List<Element.Image>) {
    val now = controller.state.value.elements.associateBy { it.id }
    adopted.forEach { image ->
        val current = now[image.id] as? Element.Image ?: return@forEach
        if (stillAdoptable(image, current)) controller.onIntent(Intent.UpdateElement(image))
    }
}

/**
 * Only if it is still the image that was adopted: moved or replaced meanwhile, it is adopted
 * again on the next pass rather than overwritten with a stale copy.
 */
private fun stillAdoptable(image: Element.Image, current: Element.Image): Boolean {
    if (image.assetRef == null) return false
    if (current.assetRef != null) return false
    return current == image.copy(assetRef = null, mediaType = null, preview = current.preview)
}

/** Which image, as stored: an image whose bytes change (replaced in place) is stored again. */
private fun storedKey(image: Element.Image): String {
    return "${image.id}:${image.bytes.size}:${image.bytes.contentHashCode()}"
}

/** A ref the host did not have is asked for again a little later, not on every recomposition. */
private suspend fun CanvasBoard.fetchMissingAssets() {
    val store = host.assets ?: return
    val s = session ?: return
    if (!ui.initialLoadDone) return
    val refs = state.elements.filterIsInstance<Element.Image>()
        .mapNotNull { it.assetRef }.distinct().filter { it !in kept.fetchingAssets }
    if (refs.isEmpty()) return
    val missing = withContext(Dispatchers.Default) { refs.filter { !store.has(it) } }
    for (ref in missing) {
        kept.fetchingAssets += ref
        scope.launch { fetchAssetBytes(s, ref) }
    }
}

private suspend fun CanvasBoard.fetchAssetBytes(s: CanvasSession, ref: String) {
    val bytes = s.fetchAsset(ref)
    if (bytes == null) {
        delay(ASSET_RETRY_MS)
        kept.fetchingAssets -= ref
        kept.assetRetry++
        return
    }
    controller.state.value.elements
        .filterIsInstance<Element.Image>()
        .filter { it.assetRef == ref && !it.bytes.contentEquals(bytes) }
        .forEach { controller.onIntent(Intent.UpdateElement(it.copy(bytes = bytes))) }
}

/** Export and error events from DrawBoxController. */
private suspend fun CanvasBoard.collectBoardEvents() {
    controller.events.collect { event -> onBoardEvent(event) }
}

private suspend fun CanvasBoard.onBoardEvent(event: Event) {
    when (event) {
        is Event.JsonExported -> onJsonExported(event.json)
        is Event.SvgExported -> onSvgExported(event.svg)
        is Event.Error -> ui.statusMessage = "Error: ${event.message.ifBlank { event.throwable?.message ?: "Unknown" }}"
        else -> Unit
    }
}

private suspend fun CanvasBoard.onJsonExported(json: String) {
    CanvasWorkspaceSupport.computeJsonExportStatus(json, wasAutosaving = false)?.let { ui.statusMessage = it }
    flushDrawing()
}

private fun CanvasBoard.onSvgExported(svg: String) {
    val callbacks = host.callbacks
    ui.statusMessage = CanvasWorkspaceSupport.computeSvgExportStatus(svg)
    callbacks.onExportSvg?.invoke(svg)
    val share = callbacks.onShareToChat ?: return
    if (!ui.isSharingToChat) return
    ui.isSharingToChat = false
    ui.statusMessage = CanvasWorkspaceSupport.handleSvgShareToChat(
        svg = svg,
        maxBytes = AttachmentLimits.Default.maxRawBytesPerImage,
        onShare = share,
    )
}
