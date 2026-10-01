package com.letta.mobile.ui.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import io.ak1.drawbox.domain.model.ResizeHandle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.X
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasTextStyle
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.compose.CanvasComposePlacement
import com.letta.mobile.data.canvas.compose.CanvasComposeReserve
import com.letta.mobile.data.canvas.compose.ComposeBounds
import io.ak1.drawbox.domain.model.Viewport
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The canvas's block documents as elements on the board, one card per document, placed in the
 * drawing's world coordinates and moving and scaling with its [viewport] so they sit among the
 * strokes like sticky notes rather than in a separate mode.
 *
 * Each card is dragged by its handle bar and resized from its corner; the frame is written to the
 * session when the gesture ends, so peers and the agent see the move as one op. A document that
 * was never placed (a legacy frameless note) gets the slot the shared placement engine gives it
 * ([framelessFrames], from `CanvasComposePlacement.placeFrameless`), so two never overlap.
 *
 * How a card is sized follows its geometry owner (canvas.compose, letta-mobile-bglj6.11): an
 * AUTO-owned or frameless note fits its content within its booked frame (see [NoteSizing]); an
 * EXPLICIT or USER frame is drawn as stored. Fitting is local: it never writes geometry back.
 *
 * Tapping a card makes it the [activeNoteId]: that one shows the block editor's toolbar and block
 * handles. Its expand button asks the host, through [onExpand], to open it large; while
 * [expandedNoteId] is open elsewhere the card only previews the text.
 */
@Composable
fun CanvasNotesLayer(
    session: CanvasSession,
    documents: List<CanvasSceneDocument>,
    viewport: Viewport,
    modifier: Modifier = Modifier,
    activeNoteId: String? = null,
    expandedNoteId: String? = null,
    onActivate: (String) -> Unit = {},
    onExpand: (String) -> Unit = {},
    onToolbar: ((NoteToolbar?) -> Unit)? = null,
    /** Notes in the board's multi-selection (marquee or shift-click); drawn with the selection border. */
    selectedIds: Set<String> = emptySet(),
    /** How far the multi-selection is being dragged right now, in world units, before it commits. */
    groupOffset: Offset = Offset.Zero,
    /** A press on a card: with Shift it toggles the card in the multi-selection instead of activating it. */
    onPress: (id: String, shift: Boolean) -> Unit = { id, _ -> onActivate(id) },
    /** Dragging a selected card moves the whole selection; the host owns that gesture. */
    onGroupDrag: ((Offset) -> Unit)? = null,
    onGroupDragEnd: (() -> Unit)? = null,
    /** True while the eraser tool is held: a press on a card removes it instead of selecting it. */
    eraseMode: Boolean = false,
    onErase: (String) -> Unit = {},
    /**
     * A card's frame while it is being dragged or resized, before the move is committed; null
     * when the gesture ends. Lets chrome anchored to a note (its menu, its quick-create targets)
     * follow the card instead of trailing at the committed position.
     */
    onLiveFrame: (id: String, frame: CanvasDocumentFrame?) -> Unit = { _, _ -> },
    /**
     * Where each frameless document sits, by id (`CanvasComposePlacement.placeFrameless` over the
     * board's content bounds). Null works it out from the notes alone, without the drawing.
     */
    framelessFrames: Map<String, CanvasDocumentFrame>? = null,
    /**
     * The height an auto-fitted card is shown at, in world units, or null once it is not fitted;
     * a group move lands each note at the size it was shown at.
     */
    onFittedHeight: (id: String, height: Float?) -> Unit = { _, _ -> },
) {
    val frameless = framelessFrames ?: remember(documents) {
        framelessFramesOf(documents) { CanvasViewportFit.contentBounds(emptyList(), documents) }
    }
    Box(modifier = modifier.fillMaxSize()) {
        documents.forEach { document ->
          key(document.id) {
            val selected = document.id in selectedIds
            CanvasNoteCard(
                session = session,
                document = document,
                viewport = viewport,
                defaultFrame = frameless[document.id] ?: framelessFallbackFrame(),
                active = document.id == activeNoteId,
                expanded = document.id == expandedNoteId,
                onActivate = { onActivate(document.id) },
                onExpand = { onExpand(document.id) },
                onToolbar = onToolbar,
                eraseMode = eraseMode,
                onErase = { onErase(document.id) },
                onLiveFrame = { frame -> onLiveFrame(document.id, frame) },
                selection = NoteSelection(
                    selected = selected,
                    groupOffset = if (selected) groupOffset else Offset.Zero,
                    onPress = { shift -> onPress(document.id, shift) },
                    onGroupDrag = onGroupDrag?.takeIf { selected },
                    onGroupDragEnd = onGroupDragEnd?.takeIf { selected },
                ),
                onFittedHeight = { height -> onFittedHeight(document.id, height) },
            )
          }
        }
    }
}

/** A card's part in the board's multi-selection. */
internal class NoteSelection(
    val selected: Boolean,
    val groupOffset: Offset,
    val onPress: (shift: Boolean) -> Unit,
    val onGroupDrag: ((Offset) -> Unit)?,
    val onGroupDragEnd: (() -> Unit)?,
)

@Composable
private fun CanvasNoteCard(
    session: CanvasSession,
    document: CanvasSceneDocument,
    viewport: Viewport,
    defaultFrame: CanvasDocumentFrame,
    active: Boolean,
    expanded: Boolean,
    onActivate: () -> Unit,
    onExpand: () -> Unit,
    onToolbar: ((NoteToolbar?) -> Unit)?,
    selection: NoteSelection,
    eraseMode: Boolean = false,
    onErase: () -> Unit = {},
    onLiveFrame: (CanvasDocumentFrame?) -> Unit = {},
    onFittedHeight: (Float?) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val recorder = LocalCanvasDocumentRecorder.current
    val density = LocalDensity.current
    var frame by remember(document.id) { mutableStateOf(document.frame ?: defaultFrame) }
    var gestureActive by remember(document.id) { mutableStateOf(false) }
    // The frame a resize started from. A text element has no card to hold its type, so dragging
    // it bigger has to make the text bigger — otherwise the box grows and the words stay put,
    // which reads as a bug rather than a scale.
    var resizeStartFrame by remember(document.id) { mutableStateOf<CanvasDocumentFrame?>(null) }
    LaunchedEffect(frame, gestureActive) { onLiveFrame(if (gestureActive) frame else null) }
    // A frameless note's slot moves when the board's content does, so it is a key too.
    LaunchedEffect(document.frame, defaultFrame) {
        if (!gestureActive) frame = document.frame ?: defaultFrame
    }

    val screenTopLeft = viewport.worldToScreen(Offset(frame.x + selection.groupOffset.x, frame.y + selection.groupOffset.y))
    val scale = viewport.scale
    val tint = parseHexColor(document.color)
    // A "plain" note (transparent colour) is text sitting on the board, and stays that way even
    // while you work in it: the translucent slab that used to appear on activation read as a
    // half-loaded card. Selection is said by the chrome now, which is what the shapes use.
    val plain = tint != null && tint.alpha == 0f
    // A shape's label is the shape's text, not a thing of its own: it sits centred in the shape
    // the way Miro sets it, and the shape carries the selection handles and the moving.
    val isLabel = plain && CanvasShapeLabels.shapeIdOf(document.id) != null
    val textStyle = if (isLabel) {
        (document.style ?: CanvasTextStyle()).let { if (it.align == null) it.copy(align = "center") else it }
    } else {
        document.style
    }
    // One padding for what is drawn and what auto-fit measures, so the two cannot drift apart.
    val textModifier = Modifier.fillMaxSize().padding(CanvasNoteChrome.bodyPadding(plain, isLabel))
    val cardColor = when {
        plain -> Color.Transparent
        else -> tint ?: MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onCard = if (tint != null && !plain) contrastOn(tint) else MaterialTheme.colorScheme.onSurfaceVariant

    // Auto-fit (letta-mobile-bglj6.11). The stored frame of an AUTO note is a booking; what the
    // card is shown at is measured here, remembered per document, and never written back.
    val sizing = noteSizing(document, isLabel)
    var fit by remember(document.id) { mutableStateOf<NoteFit?>(null) }
    // A person's move or resize of a fitted card takes it over at the size and type it was SHOWN
    // at; until that op lands, the card stays at what the person left it at instead of refitting.
    var gestureFitScale by remember(document.id) { mutableStateOf(1f) }
    var userTook by remember(document.id) { mutableStateOf(false) }
    LaunchedEffect(sizing) { if (sizing == NoteSizing.VERBATIM) userTook = false }
    val fitting = sizing == NoteSizing.FIT && !gestureActive && !userTook
    val shownFit = if (fitting) fit else null
    val fitScale = when {
        sizing != NoteSizing.FIT -> 1f
        fitting -> shownFit?.fontScale ?: 1f
        else -> gestureFitScale
    }
    val shownStyle = if (sizing == NoteSizing.FIT) CanvasNoteAutoFit.scaledStyle(textStyle, fitScale) else textStyle
    LaunchedEffect(sizing, fit) { onFittedHeight(if (sizing == NoteSizing.FIT) fit?.height else null) }
    // While it is typed into, the card keeps the type size it had and never gets shorter, so a
    // deleted line or a type-size step never moves the text under the caret.
    val activeFit = remember(document.id, active) { if (active) fit else null }
    val storedFrame = document.frame ?: defaultFrame

    val widthDp = with(density) { frame.width.toDp() }
    val heightDp = with(density) { (shownFit?.height ?: frame.height).toDp() }

    /** A gesture on a fitted card starts from what is shown, not from the booking under it. */
    fun beginGesture() {
        if (!gestureActive && fitting) {
            shownFit?.let {
                frame = frame.copy(height = it.height)
                gestureFitScale = it.fontScale
            }
        }
        gestureActive = true
    }

    // Drag deltas arrive in the card's own (unscaled) space because the scale is a graphics-layer
    // transform, so they are already world units.
    fun commit() {
        gestureActive = false
        val committed = frame
        val started = resizeStartFrame
        resizeStartFrame = null
        // Scale the type by however much the box grew, height being what type is measured by.
        // A fitted card set smaller to fit keeps that size once a person takes it over.
        val fitStyle = if (sizing == NoteSizing.FIT && gestureFitScale != 1f) {
            CanvasNoteAutoFit.scaledStyle(document.style, gestureFitScale)
        } else {
            null
        }
        val scaledStyle = computeScaledStyle(plain, started, committed, fitStyle ?: document.style) ?: fitStyle
        if (sizing == NoteSizing.FIT && committed != document.frame) userTook = true
        scope.launch {
            recorder.recordingOrJust("moving a note") {
                // One commit, not two. A frame op followed by a style op can half-succeed, leaving
                // a box that grew with type that did not - and the second op runs even when the
                // first has already failed.
                runCatching {
                    session.moveDocument(document.id, committed, style = scaledStyle)
                }
            }
        }
    }

    if (isLabel && !active) {
        // Not being typed into, a shape's text is only shown: drawn and never hit, so a press
        // anywhere in the shape reaches the shape - to pick it up, drag it or double-click into
        // its text - instead of landing on a card in front of it.
        Box(
            modifier = Modifier
                .offset { IntOffset(screenTopLeft.x.roundToInt(), screenTopLeft.y.roundToInt()) }
                .size(width = widthDp, height = heightDp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .semantics { contentDescription = "Note ${document.id}" },
            contentAlignment = Alignment.Center,
        ) {
            CanvasBlockPreview(
                json = document.json,
                style = textStyle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.xs),
            )
        }
        return
    }

    // The card and its selection chrome share one placed, scaled box, and the box carries the
    // chrome's margin on every side: the chrome sits OUTSIDE the card, and a handle hanging past
    // its parent's bounds is drawn but never hit, which is how the handles came to look draggable
    // without being draggable.
    // In the card's own (zoomed) units, so the margin is one size on screen like the chrome in it.
    val chromeInset = canvasSelectionStyle().chromeInset() / (if (scale <= 0f) 1f else scale)
    val insetPx = with(density) { chromeInset.toPx() } * scale
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (screenTopLeft.x - insetPx).roundToInt(),
                    (screenTopLeft.y - insetPx).roundToInt(),
                )
            }
            .size(width = widthDp + chromeInset * 2, height = heightDp + chromeInset * 2)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
    if (sizing == NoteSizing.FIT) {
        // Measured at the STORED width and booking, not the live frame, so a drag never remeasures.
        NoteFitMeasurer(
            NoteFitRequest(
                json = document.json,
                style = textStyle,
                onLightSurface = tint != null && !plain,
                plain = plain,
                width = storedFrame.width,
                reserved = storedFrame.height,
                fixedScale = activeFit?.fontScale,
                minHeight = activeFit?.height ?: 0f,
            ),
        ) { measured -> if (fit != measured) fit = measured }
    }
    Surface(
        modifier = Modifier
            .padding(chromeInset)
            .size(width = widthDp, height = heightDp)
            .semantics {
                contentDescription = "Note ${document.id}"
                if (sizing == NoteSizing.FIT) noteFontScale = fitScale
            }
            // Taps and drags on the card belong to the note, never to the drawing beneath it; a
            // tap anywhere on it (the editor's own taps included, in the initial pass) makes it
            // the active note.
            // eraseMode is a key, not just a capture: a pointerInput block keeps the values it
            // was created with, so keying on the id alone left this handler believing the eraser
            // was never active.
            .pointerInput(document.id, eraseMode) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (eraseMode) onErase() else selection.onPress(currentEvent.keyboardModifiers.isShiftPressed)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(document.id) { detectTapGestures(onTap = {}) },
        shape = RoundedCornerShape(NOTE_CORNER),
        color = cardColor,
        // Selection is drawn by CanvasSelectionChrome, the same chrome a shape gets. The card's
        // own border is only the resting outline of a coloured note.
        border = when {
            active || selection.selected -> null
            plain -> null
            else -> BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        },
        // Text on the board is text, not a card, so it never casts one - active or not. A
        // transparent surface with an elevation does not simply skip the shadow: Compose draws
        // the shadow body anyway, and a grey slab appeared behind the words the moment they were
        // edited.
        shadowElevation = when {
            plain -> 0.dp
            active -> LettaDimens.Space.sm
            else -> LettaDimens.Space.xs
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // A note has a handle bar; a text element is just text, with a grip to move it by
            // while active and everything else in the bar at the top of the board.
            val groupDrag = selection.onGroupDrag
            val groupDragEnd = selection.onGroupDragEnd
            val onMove: (Offset) -> Unit = if (groupDrag != null) groupDrag else { delta -> frame = frame.copy(x = frame.x + delta.x, y = frame.y + delta.y) }
            val onMoveEnd: () -> Unit = if (groupDragEnd != null) groupDragEnd else ::commit
            if (!plain) NoteHandleBar(
                title = document.title,
                cardColor = cardColor,
                onCard = onCard,
                onDragStart = { if (groupDrag == null) beginGesture() },
                onDrag = onMove,
                onDragEnd = onMoveEnd,
                onExpand = onExpand,
                onRemove = {
                    scope.launch {
                        recorder.recordingOrJust("deleting a note") {
                            runCatching { session.removeDocument(document.id) }
                        }
                    }
                },
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = if (isLabel) Alignment.Center else Alignment.TopStart,
            ) {
                if (expanded) {
                    CanvasBlockPreview(
                        json = document.json,
                        onLightSurface = tint != null && !plain,
                        style = shownStyle,
                        modifier = textModifier,
                    )
                } else {
                    CanvasBlockEditor(
                        session = session,
                        documentId = document.id,
                        storedJson = document.json,
                        active = active,
                        onLightSurface = tint != null && !plain,
                        onToolbar = onToolbar,
                        style = shownStyle,
                        centerVertically = isLabel,
                        modifier = textModifier,
                    )
                }
                if (plain && active && !isLabel) {
                    TextMoveGrip(
                        modifier = Modifier.align(Alignment.TopStart),
                        onDragStart = { if (groupDrag == null) beginGesture() },
                        onDrag = onMove,
                        onDragEnd = onMoveEnd,
                    )
                }
            }
        }
    }

        // The same chrome a selected shape gets — outline, eight handles, and resize — over the
        // card and reaching outside it, so the outer half of each handle can still be grabbed.
        if ((active || selection.selected) && !isLabel) {
            CanvasSelectionChrome(
                style = canvasSelectionStyle(),
                scale = scale,
                contentWidth = widthDp,
                contentHeight = heightDp,
                onResize = { handle, delta ->
                    beginGesture()
                    if (resizeStartFrame == null) resizeStartFrame = frame
                    frame = frame.resizedBy(handle, delta)
                },
                onResizeEnd = ::commit,
            )
        }
    }
}

/**
 * A drag that reports its deltas and treats cancel as an end: the one gesture the handle bar,
 * the text grip and the resize corner all share.
 */
private fun Modifier.dragHandle(onDragStart: () -> Unit, onDrag: (Offset) -> Unit, onDragEnd: () -> Unit): Modifier =
    pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { onDragStart() },
            onDragEnd = onDragEnd,
            onDragCancel = onDragEnd,
            onDrag = { change, dragAmount ->
                change.consume()
                onDrag(dragAmount)
            },
        )
    }

@Composable
private fun NoteHandleBar(
    title: String?,
    cardColor: Color,
    onCard: Color,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onExpand: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HANDLE_HEIGHT)
            .background(onCard.copy(alpha = 0.08f))
            .dragHandle(onDragStart, onDrag, onDragEnd)
            .padding(start = LettaDimens.Space.sm, end = LettaDimens.Space.hair),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.GripVertical,
            contentDescription = "Move note",
            modifier = Modifier.size(LettaDimens.Control.icon),
            tint = onCard,
        )
        Spacer(modifier = Modifier.size(LettaDimens.Space.sm))
        Text(
            text = title?.takeIf { it.isNotBlank() } ?: "Note",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = onCard,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onExpand, modifier = Modifier.size(HANDLE_HEIGHT)) {
            Icon(
                imageVector = Lucide.Maximize2,
                contentDescription = "Open note",
                modifier = Modifier.size(LettaDimens.Control.icon),
                tint = onCard,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(HANDLE_HEIGHT)) {
            Icon(
                imageVector = Lucide.X,
                contentDescription = "Remove note",
                modifier = Modifier.size(LettaDimens.Control.icon),
                tint = onCard,
            )
        }
    }
}

/** The grip a text element is moved by: a small handle in its top-left corner while active. */
@Composable
private fun TextMoveGrip(
    modifier: Modifier,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(LettaDimens.Control.icon)
            .dragHandle(onDragStart, onDrag, onDragEnd)
            .semantics { contentDescription = "Move text" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Lucide.GripVertical,
            contentDescription = null,
            modifier = Modifier.size(LettaDimens.Control.icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Where each never-placed (frameless) document of [documents] lands, by id: the shared placement
 * engine's slots (`CanvasComposePlacement.placeFrameless`), laid out against [contentBounds] (the
 * board's zoom-to-fit bounds, which agree with the engine's JSON reading of the same scene). The
 * slots are deterministic over the scene, so every peer puts them in the same place, and they never
 * overlap one another. Replaces the old stagger by list index, which stacked notes on each other.
 */
internal fun framelessFramesOf(
    documents: List<CanvasSceneDocument>,
    contentBounds: () -> androidx.compose.ui.geometry.Rect?,
): Map<String, CanvasDocumentFrame> {
    if (documents.none { it.frame == null }) return emptyMap()
    val bounds = contentBounds()?.let { ComposeBounds(it.left, it.top, it.width, it.height) }
    return CanvasComposePlacement.placeFrameless(documents, bounds).mapValues { (_, slot) ->
        CanvasDocumentFrame(x = slot.x, y = slot.y, width = slot.width, height = slot.height)
    }
}

/** A frameless document the placement has no slot for yet: the engine's empty-board origin. */
internal fun framelessFallbackFrame(): CanvasDocumentFrame = CanvasDocumentFrame(
    x = CanvasComposePlacement.ORIGIN,
    y = CanvasComposePlacement.ORIGIN,
    width = CanvasComposePlacement.FRAMELESS_WIDTH,
    height = CanvasComposeReserve.MIN_RESERVE,
)

/**
 * [wanted], moved clear of anything already sitting at that spot.
 *
 * Every new note is placed at the middle of the board, so the second one lands exactly on top of
 * the first: it hides it and takes every click meant for it. The note underneath cannot be typed
 * in, ticked or picked up, which reads as that note being broken rather than covered.
 */
internal fun clearOfExisting(
    wanted: CanvasDocumentFrame,
    taken: List<CanvasDocumentFrame>,
): CanvasDocumentFrame {
    var frame = wanted
    var moves = 0
    while (moves < MAX_CASCADE && taken.any { it.sharesOrigin(frame) }) {
        frame = frame.copy(x = frame.x + NOTE_STAGGER, y = frame.y + NOTE_STAGGER)
        moves++
    }
    return frame
}

private fun CanvasDocumentFrame.sharesOrigin(other: CanvasDocumentFrame): Boolean =
    x == other.x && y == other.y

/** A frame for a new note centred on [worldCenter]. */
internal fun newNoteFrame(worldCenter: Offset): CanvasDocumentFrame = CanvasDocumentFrame(
    x = worldCenter.x - NOTE_DEFAULT_WIDTH / 2f,
    y = worldCenter.y - NOTE_DEFAULT_HEIGHT / 2f,
    width = NOTE_DEFAULT_WIDTH,
    height = NOTE_DEFAULT_HEIGHT,
)

/** A frame for a new plain text block centred on [worldCenter]: wider and shorter than a note. */
internal fun newTextFrame(worldCenter: Offset): CanvasDocumentFrame = CanvasDocumentFrame(
    x = worldCenter.x - TEXT_DEFAULT_WIDTH / 2f,
    y = worldCenter.y - TEXT_DEFAULT_HEIGHT / 2f,
    width = TEXT_DEFAULT_WIDTH,
    height = TEXT_DEFAULT_HEIGHT,
)

/** The colour that marks a plain text block: fully transparent, so no card is drawn. */
internal const val PLAIN_TEXT_COLOR = "#00000000"

internal const val NOTE_DEFAULT_WIDTH = 320f
internal const val NOTE_DEFAULT_HEIGHT = 240f
private const val TEXT_DEFAULT_WIDTH = 360f
private const val TEXT_DEFAULT_HEIGHT = 120f
// Far enough that the note underneath keeps its first line clear of the new note's corner handle,
// whose grab area is a shape handle's (see canvasSelectionStyle), not just the handle drawn.
private const val NOTE_STAGGER = 64f

/** How far a new note will cascade before it is left to overlap: a board can be crowded. */
private const val MAX_CASCADE = 24
private const val NOTE_MIN_SIZE = 140f
private val NOTE_CORNER = LettaDimens.Radius.md
private val HANDLE_HEIGHT = LettaDimens.Control.iconButton

/**
 * This frame after dragging [handle] by [delta], in world units.
 *
 * A side handle moves one edge, a corner moves two. An edge being dragged past its opposite is
 * clamped at [NOTE_MIN_SIZE] rather than inverting the frame, which is what a shape does too.
 */
internal fun CanvasDocumentFrame.resizedBy(handle: ResizeHandle, delta: Offset): CanvasDocumentFrame {
    val (newX, newWidth) = resizeHorizontal(x, width, handle, delta.x)
    val (newY, newHeight) = resizeVertical(y, height, handle, delta.y)
    return copy(x = newX, y = newY, width = newWidth, height = newHeight)
}

private fun resizeHorizontal(x: Float, width: Float, handle: ResizeHandle, dx: Float): Pair<Float, Float> = when {
    handle.movesLeft -> {
        val clamped = dx.coerceAtMost(width - NOTE_MIN_SIZE)
        (x + clamped) to (width - clamped)
    }
    handle.movesRight -> x to (width + dx).coerceAtLeast(NOTE_MIN_SIZE)
    else -> x to width
}

private fun resizeVertical(y: Float, height: Float, handle: ResizeHandle, dy: Float): Pair<Float, Float> = when {
    handle.movesTop -> {
        val clamped = dy.coerceAtMost(height - NOTE_MIN_SIZE)
        (y + clamped) to (height - clamped)
    }
    handle.movesBottom -> y to (height + dy).coerceAtLeast(NOTE_MIN_SIZE)
    else -> y to height
}

private val ResizeHandle.movesLeft: Boolean
    get() = when (this) {
        ResizeHandle.TopLeft, ResizeHandle.Left, ResizeHandle.BottomLeft -> true
        else -> false
    }

private val ResizeHandle.movesRight: Boolean
    get() = when (this) {
        ResizeHandle.TopRight, ResizeHandle.Right, ResizeHandle.BottomRight -> true
        else -> false
    }

private val ResizeHandle.movesTop: Boolean
    get() = when (this) {
        ResizeHandle.TopLeft, ResizeHandle.Top, ResizeHandle.TopRight -> true
        else -> false
    }

private val ResizeHandle.movesBottom: Boolean
    get() = when (this) {
        ResizeHandle.BottomLeft, ResizeHandle.Bottom, ResizeHandle.BottomRight -> true
        else -> false
    }

/** Type may be scaled this far by dragging a text element's box, and no further. */
private const val MIN_FONT_SCALE = 0.4f
private const val MAX_FONT_SCALE = 8f

private fun computeScaledStyle(
    plain: Boolean,
    started: CanvasDocumentFrame?,
    committed: CanvasDocumentFrame,
    currentStyle: CanvasTextStyle?,
): CanvasTextStyle? {
    if (!plain) return null
    if (started == null) return null
    if (started.height <= 0f) return null
    val factor = committed.height / started.height
    if (factor == 1f) return null
    val style = currentStyle ?: CanvasTextStyle()
    val baseScale = style.fontScale ?: 1f
    return style.copy(fontScale = (baseScale * factor).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE))
}
