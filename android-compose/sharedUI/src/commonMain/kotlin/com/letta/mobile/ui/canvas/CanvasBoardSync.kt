package com.letta.mobile.ui.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.data.attachment.AttachmentLimits
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasHistory
import com.letta.mobile.data.canvas.CanvasOpProjector
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.util.Telemetry
import io.ak1.drawbox.domain.model.DrawingSerializer
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Event
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.PayLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** How long before asking the host again for an asset it did not have yet. */
private const val ASSET_RETRY_MS = 10_000L

/** How long the board waits after loading before it takes its undo baseline. */
private const val LOAD_SETTLE_MS = 100L

/** How long the board waits for changes to settle before it saves them. */
private const val AUTOSAVE_DEBOUNCE_MS = 500L

/**
 * The board's work with its session and its drawing: folding old labels, the background, the
 * load and the live sync, the autosave, the images and the export events. Launched in this
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
    LaunchedEffect(session, board.host.initialJson) { board.loadBoard(this) }
    // Autosave: debounced on a dirty signal (elements or background colour change), then
    // exportJson(); the resulting Event.JsonExported persists the updated scene off the main
    // thread. The background colour is part of the exported drawing, and the export is the only
    // way it becomes an op: keyed on the elements alone, a new colour stayed on this board until
    // the next stroke carried it along.
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
private suspend fun CanvasBoard.loadBoard(effectScope: CoroutineScope) {
    kept.history.clear()
    val s = session
    if (s == null) {
        loadPreview()
        return
    }
    host.sessionRegistry?.register(s)
    s.startSync(effectScope)
    try {
        openSession(s)
    } finally {
        host.sessionRegistry?.unregister(s.canvasId)
    }
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
    s.load()
    val sessionJson = s.sceneJsonOrEmpty()
    var lastImportedRev = s.document.value?.revision ?: 0L
    // Parsing the scene is the heaviest thing opening a board does, and a board with pictures on
    // it is megabytes of JSON: it is parsed off the main thread, and only the parsed drawing is
    // handed to the controller on it.
    val (clean, parsed) = withContext(Dispatchers.Default) { parseOpenedScene(sessionJson) }
    // Known even for an empty canvas, or the first note placed on it would read as an external
    // change to the drawing and reload the board.
    ui.lastDrawing = clean
    if (sessionJson.isNotBlank()) {
        importOpenedScene(OpenedScene(sessionJson, clean, parsed), s, openStarted)
    }
    ui.importedRevision = lastImportedRev
    delay(LOAD_SETTLE_MS)
    // The baseline is the board AS LOADED, set before any edit is accepted. Left null until the
    // first save, the first drawing change only initialises it and records nothing - so a note
    // done first and a stroke done second undid in the wrong order.
    ui.lastSavedElements = controller.state.value.elements
    ui.initialLoadDone = true
    // Each revision is compared and parsed off the main thread too. While one is being parsed the
    // collector is suspended, and the document flow keeps only the newest revision, so a catch-up
    // burst of many revisions lands as one import. An agent's replace wins.
    s.document.collect { doc -> lastImportedRev = syncExternalRevision(doc, lastImportedRev) }
}

/** The scene a session opened with: as stored, stripped for DrawBox, and parsed. */
private class OpenedScene(val json: String, val clean: String, val parsed: PayLoad?)

/**
 * Elements DrawBox cannot read are dropped (and reported) first: one of them would otherwise fail
 * the whole scene and open an empty board. A scene that breaks the board's state rules is
 * reported too (see the scene guard).
 */
private fun CanvasBoard.parseOpenedScene(sessionJson: String): Pair<String, PayLoad?> {
    val stripped = CanvasOpProjector.stripMetadataForDrawBox(sessionJson)
    val drawable = kept.sceneGuard.drawable(sessionJson)
    return stripped to if (sessionJson.isBlank()) null else CanvasImageAssets.parse(drawable, host.assets)
}

private fun CanvasBoard.importOpenedScene(
    scene: OpenedScene,
    s: CanvasSession,
    openStarted: TimeSource.Monotonic.ValueTimeMark,
) {
    // A scene that does not parse still goes through the text path, which reports it.
    val parsed = scene.parsed
    if (parsed != null) controller.importPath(parsed) else controller.importPath(scene.clean)
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
private suspend fun CanvasBoard.syncExternalRevision(doc: CanvasDocument?, lastImportedRev: Long): Long {
    val importStarted = TimeSource.Monotonic.markNow()
    val params = ExternalSyncParams(
        doc = doc,
        lastImportedRev = lastImportedRev,
        lastExportedJson = ui.lastExportedJson,
        lastDrawing = ui.lastDrawing,
    )
    val (result, parsedExternal) = withContext(Dispatchers.Default) { evaluateExternal(params) } ?: return lastImportedRev
    ui.importedRevision = result.newImportedRev
    val cleanJson = result.cleanJson?.takeIf { result.shouldImport } ?: return result.newImportedRev
    importExternalScene(ExternalScene(doc, cleanJson, parsedExternal), importStarted)
    result.statusMessage?.let { ui.statusMessage = it }
    return result.newImportedRev
}

private fun CanvasBoard.evaluateExternal(params: ExternalSyncParams): Pair<ExternalSyncResult, PayLoad?>? {
    val evaluated = CanvasWorkspaceSupport.evaluateExternalDocSync(params) ?: return null
    val payload = evaluated.cleanJson?.takeIf { evaluated.shouldImport }
        ?.let { CanvasImageAssets.parse(kept.sceneGuard.drawable(params.doc?.sceneJson.orEmpty()), host.assets) }
    return evaluated to payload
}

/** A revision from elsewhere, stripped for DrawBox and parsed. */
private class ExternalScene(val doc: CanvasDocument?, val cleanJson: String, val parsed: PayLoad?)

private fun CanvasBoard.importExternalScene(scene: ExternalScene, importStarted: TimeSource.Monotonic.ValueTimeMark) {
    // A change from another app must not move this one's camera or tool.
    val parsed = scene.parsed
    if (parsed != null) controller.importExternal(parsed) else controller.importExternal(scene.cleanJson)
    Telemetry.event(
        "CanvasPerformance", "sync.imported",
        "revision" to scene.doc?.revision,
        "sceneChars" to scene.cleanJson.length,
        durationMs = importStarted.elapsedNow().inWholeMilliseconds,
        level = Telemetry.Level.DEBUG,
    )
    // Nor put a caret in text placed there: it is known before the caret effect sees it, or text
    // placed on a desktop opened a phone's keyboard.
    kept.knownTextIds = CanvasTextElements.ids(controller.state.value.elements)
    ui.lastDrawing = scene.cleanJson
}

private suspend fun CanvasBoard.autosave() {
    if (!ui.initialLoadDone || session == null) return
    delay(AUTOSAVE_DEBOUNCE_MS)
    ui.isAutosaving = true
    controller.exportJson()
}

/**
 * The image itself is given its ref (and a preview) only once drawings stop carrying image bytes
 * (DrawingSerializer.inlineImageBytes off, w3nb2.3c), when every app reads refs. Before then,
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
    ui.lastExportedJson = json
    val wasAutosaving = ui.isAutosaving
    ui.isAutosaving = false
    CanvasWorkspaceSupport.computeJsonExportStatus(json, wasAutosaving)?.let { ui.statusMessage = it }
    val s = session ?: return
    if (s.sceneJsonOrEmpty() == json) return
    ui.lastDrawing = CanvasOpProjector.stripMetadataForDrawBox(json)
    withContext(Dispatchers.Default) { s.applyLocalScene(json) }
    ui.lastDrawing = CanvasOpProjector.stripMetadataForDrawBox(s.sceneJsonOrEmpty())
    recordDrawingStep()
}

/** A saved drawing change is one undo step, unless undo or redo made it. */
private fun CanvasBoard.recordDrawingStep() {
    val elementsNow = controller.state.value.elements
    val shouldRecord = CanvasWorkspaceSupport.shouldRecordDrawingStep(
        elementsBefore = ui.lastSavedElements,
        elementsNow = elementsNow,
        isApplyingHistory = ui.applyingHistory,
    )
    ui.lastSavedElements = elementsNow
    if (ui.applyingHistory) {
        ui.applyingHistory = false
    } else if (shouldRecord) {
        kept.history.record(CanvasHistory.Step.Drawing())
    }
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
