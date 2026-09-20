package com.letta.mobile.ui.canvas

import kotlin.math.abs
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
 * was never placed gets a staggered default spot until someone moves it.
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
    /** A press that selects rather than activates - what a text element does, being an object first. */
    onSelect: (id: String, shift: Boolean) -> Unit = { id, shift -> onPress(id, shift) },
    /** Dragging a selected card moves the whole selection; the host owns that gesture. */
    onGroupDrag: ((Offset) -> Unit)? = null,
    onGroupDragEnd: (() -> Unit)? = null,
    /** True while the eraser tool is held: a press on a card removes it instead of selecting it. */
    eraseMode: Boolean = false,
    onErase: (String) -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        documents.forEachIndexed { index, document ->
            val selected = document.id in selectedIds
            CanvasNoteCard(
                session = session,
                document = document,
                viewport = viewport,
                defaultFrame = defaultNoteFrame(index),
                active = document.id == activeNoteId,
                expanded = document.id == expandedNoteId,
                onActivate = { onActivate(document.id) },
                onExpand = { onExpand(document.id) },
                onToolbar = onToolbar,
                eraseMode = eraseMode,
                onErase = { onErase(document.id) },
                selection = NoteSelection(
                    selected = selected,
                    groupOffset = if (selected) groupOffset else Offset.Zero,
                    onPress = { shift -> onPress(document.id, shift) },
                    onSelect = { shift -> onSelect(document.id, shift) },
                    onGroupDrag = onGroupDrag?.takeIf { selected },
                    onGroupDragEnd = onGroupDragEnd?.takeIf { selected },
                ),
            )
        }
    }
}

/** A card's part in the board's multi-selection. */
internal class NoteSelection(
    val selected: Boolean,
    val groupOffset: Offset,
    val onPress: (shift: Boolean) -> Unit,
    /**
     * A press that picks the card up as an object rather than putting the caret in it. Text uses
     * this - it is selected on a press and edited on a double click, the way a shape is - so the
     * eight handles are the one control for moving and resizing everything on the board.
     */
    val onSelect: (shift: Boolean) -> Unit,
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
    LaunchedEffect(document.frame) {
        if (!gestureActive) frame = document.frame ?: defaultFrame
    }

    val screenTopLeft = viewport.worldToScreen(Offset(frame.x + selection.groupOffset.x, frame.y + selection.groupOffset.y))
    val scale = viewport.scale
    val tint = parseHexColor(document.color)
    // A "plain" note (transparent colour) is text sitting on the board, and stays that way even
    // while you work in it: the translucent slab that used to appear on activation read as a
    // half-loaded card. Selection is said by the chrome now, which is what the shapes use.
    val plain = tint != null && tint.alpha == 0f
    val cardColor = when {
        plain -> Color.Transparent
        else -> tint ?: MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onCard = if (tint != null && !plain) contrastOn(tint) else MaterialTheme.colorScheme.onSurfaceVariant
    val widthDp = with(density) { frame.width.toDp() }
    val heightDp = with(density) { frame.height.toDp() }

    // Drag deltas arrive in the card's own (unscaled) space because the scale is a graphics-layer
    // transform, so they are already world units.
    fun commit() {
        gestureActive = false
        val committed = frame
        val started = resizeStartFrame
        resizeStartFrame = null
        // Scale the type by however much the box grew, height being what type is measured by.
        val scaledStyle = if (plain && started != null && started.height > 0f) {
            val factor = committed.height / started.height
            if (factor == 1f) {
                null
            } else {
                val style = document.style ?: CanvasTextStyle()
                style.copy(fontScale = ((style.fontScale ?: 1f) * factor).coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE))
            }
        } else {
            null
        }
        scope.launch {
            recorder.recordingOrJust("moving a note") {
                // One commit, not two. A frame op followed by a style op can half-succeed, leaving
                // a box that grew with type that did not - and the second op runs even when the
                // first has already failed.
                runCatching {
                    session.setDocument(document.id, document.json, frame = committed, style = scaledStyle)
                }
            }
        }
    }

    // The card and its selection chrome share one placed, scaled box, and the box carries the
    // chrome's margin on every side: the chrome sits OUTSIDE the card, and a handle hanging past
    // its parent's bounds is drawn but never hit, which is how the handles came to look draggable
    // without being draggable.
    val chromeInset = canvasSelectionStyle().chromeInset()
    val insetPx = with(density) { chromeInset.toPx() } * scale

    // A text element is the words, not a card holding them: its box is whatever they need, so the
    // chrome sits ON the text instead of around an empty note-sized frame. Measured from what was
    // laid out, and ignored while a gesture owns the frame - a resize would otherwise fight the fit.
    var fittedPx by remember(document.id) { mutableStateOf<IntSize?>(null) }
    val fitted = fittedPx.takeIf { plain && !gestureActive }
    val boxWidthDp = fitted?.let { with(density) { it.width.toDp() } } ?: widthDp
    val boxHeightDp = fitted?.let { with(density) { it.height.toDp() } } ?: heightDp

    // The fit is written back, so the frame the eraser, the pen and the connectors test against is
    // the box on screen. moveDocuments carries the frame alone and reads the text from the session,
    // so a fit landing mid-sentence cannot overwrite what is being typed. Not recorded: it is a
    // consequence of the typing, which is a step of its own already.
    LaunchedEffect(document.id, fitted, plain) {
        val size = fitted ?: return@LaunchedEffect
        val current = document.frame ?: defaultFrame
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        if (abs(current.width - width) < FIT_EPSILON && abs(current.height - height) < FIT_EPSILON) return@LaunchedEffect
        val fittedFrame = current.copy(width = width, height = height)
        frame = fittedFrame
        runCatching { session.moveDocuments(mapOf(document.id to fittedFrame)) }
    }

    val groupDrag = selection.onGroupDrag
    val groupDragEnd = selection.onGroupDragEnd
    val onMove: (Offset) -> Unit = if (groupDrag != null) groupDrag else { delta -> frame = frame.copy(x = frame.x + delta.x, y = frame.y + delta.y) }
    val onMoveEnd: () -> Unit = if (groupDragEnd != null) groupDragEnd else ::commit

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (screenTopLeft.x - insetPx).roundToInt(),
                    (screenTopLeft.y - insetPx).roundToInt(),
                )
            }
            .size(width = boxWidthDp + chromeInset * 2, height = boxHeightDp + chromeInset * 2)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
    // Text is picked up the way a shape is: resting, it is an object - press it and the chrome is
    // around the words, drag it anywhere to move it - and the caret is asked for with a double
    // click. A single press used to put the caret in, which is why text needed a grip of its own
    // to be movable at all; the grip is gone and the handles are the one control.
    val textAsObject = plain && !active
    Surface(
        modifier = Modifier
            .padding(chromeInset)
            .then(
                if (plain) {
                    Modifier.widthIn(max = widthDp).wrapContentSize()
                } else {
                    Modifier.size(width = widthDp, height = heightDp)
                },
            )
            .onSizeChanged { if (plain) fittedPx = it }
            .semantics { contentDescription = "Note ${document.id}" }
            // Taps and drags on the card belong to the note, never to the drawing beneath it; a
            // tap anywhere on it (the editor's own taps included, in the initial pass) makes it
            // the active note.
            // eraseMode is a key, not just a capture: a pointerInput block keeps the values it
            // was created with, so keying on the id alone left this handler believing the eraser
            // was never active.
            .pointerInput(document.id, eraseMode, textAsObject) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val shift = currentEvent.keyboardModifiers.isShiftPressed
                    when {
                        eraseMode -> onErase()
                        textAsObject -> selection.onSelect(shift)
                        else -> selection.onPress(shift)
                    }
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                }
            }
            .then(
                // Resting text moves by being dragged, like the shape it now behaves as.
                if (textAsObject) {
                    Modifier.dragHandle(
                        onDragStart = { if (groupDrag == null) gestureActive = true },
                        onDrag = onMove,
                        onDragEnd = onMoveEnd,
                    )
                } else {
                    Modifier
                },
            )
            .pointerInput(document.id, textAsObject) {
                detectTapGestures(
                    onDoubleTap = if (textAsObject) ({ _ -> onActivate() }) else null,
                    onTap = {},
                )
            },
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
        if (plain) {
            // Text, and nothing else: no bar, no card, no grip - just the words, padded by a hair
            // so the chrome does not sit on the glyphs. Resting text is drawn rather than edited,
            // so a press cannot land in a text field and give back the caret the double click is
            // there to ask for.
            if (textAsObject) {
                CanvasBlockPreview(
                    json = document.json,
                    onLightSurface = false,
                    style = document.style,
                    modifier = Modifier.padding(TEXT_INSET),
                )
            } else {
                CanvasBlockEditor(
                    session = session,
                    documentId = document.id,
                    storedJson = document.json,
                    active = active,
                    onLightSurface = false,
                    onToolbar = onToolbar,
                    style = document.style,
                    modifier = Modifier.padding(TEXT_INSET),
                )
            }
        } else Column(modifier = Modifier.fillMaxSize()) {
            NoteHandleBar(
                cardColor = cardColor,
                onCard = onCard,
                onDragStart = { if (groupDrag == null) gestureActive = true },
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
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (expanded) {
                    CanvasBlockPreview(
                        json = document.json,
                        onLightSurface = tint != null,
                        style = document.style,
                        modifier = Modifier.fillMaxSize().padding(start = LettaDimens.Space.md, end = LettaDimens.Space.md, top = LettaDimens.Space.xs, bottom = LettaDimens.Space.xs),
                    )
                } else {
                    CanvasBlockEditor(
                        session = session,
                        documentId = document.id,
                        storedJson = document.json,
                        active = active,
                        onLightSurface = tint != null,
                        onToolbar = onToolbar,
                        style = document.style,
                        modifier = Modifier.fillMaxSize().padding(start = LettaDimens.Space.md, end = LettaDimens.Space.md, top = LettaDimens.Space.xs, bottom = LettaDimens.Space.xs),
                    )
                }
            }
        }
    }

        // The same chrome a selected shape gets — outline, eight handles, and resize — over the
        // card and reaching outside it, so the outer half of each handle can still be grabbed.
        if (active || selection.selected) {
            CanvasSelectionChrome(
                style = canvasSelectionStyle(),
                scale = scale,
                contentWidth = boxWidthDp,
                contentHeight = boxHeightDp,
                onResize = { handle, delta ->
                    if (resizeStartFrame == null) resizeStartFrame = frame
                    gestureActive = true
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
            text = "Note",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = onCard,
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

/** Where the [index]th never-placed document lands: a stagger so several stay visible. */
internal fun defaultNoteFrame(index: Int): CanvasDocumentFrame = CanvasDocumentFrame(
    x = NOTE_DEFAULT_ORIGIN + index * NOTE_STAGGER,
    y = NOTE_DEFAULT_ORIGIN + index * NOTE_STAGGER,
    width = NOTE_DEFAULT_WIDTH,
    height = NOTE_DEFAULT_HEIGHT,
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
    while (moves < MAX_CASCADE && taken.any { it.x == frame.x && it.y == frame.y }) {
        frame = frame.copy(x = frame.x + NOTE_STAGGER, y = frame.y + NOTE_STAGGER)
        moves++
    }
    return frame
}

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
private const val NOTE_DEFAULT_ORIGIN = 80f
private const val NOTE_STAGGER = 40f

/** How far a new note will cascade before it is left to overlap: a board can be crowded. */
private const val MAX_CASCADE = 24
private const val NOTE_MIN_SIZE = 140f
private val NOTE_CORNER = LettaDimens.Radius.md

/** How far the chrome stands off the glyphs of a text element. */
private val TEXT_INSET = LettaDimens.Space.xs

/** Below this, a re-measure is the same box and writing it back would be churn. */
private const val FIT_EPSILON = 0.5f
private val HANDLE_HEIGHT = LettaDimens.Control.iconButton

/**
 * This frame after dragging [handle] by [delta], in world units.
 *
 * A side handle moves one edge, a corner moves two. An edge being dragged past its opposite is
 * clamped at [NOTE_MIN_SIZE] rather than inverting the frame, which is what a shape does too.
 */
internal fun CanvasDocumentFrame.resizedBy(handle: ResizeHandle, delta: Offset): CanvasDocumentFrame {
    val movesLeft = handle == ResizeHandle.TopLeft || handle == ResizeHandle.Left || handle == ResizeHandle.BottomLeft
    val movesRight = handle == ResizeHandle.TopRight || handle == ResizeHandle.Right || handle == ResizeHandle.BottomRight
    val movesTop = handle == ResizeHandle.TopLeft || handle == ResizeHandle.Top || handle == ResizeHandle.TopRight
    val movesBottom = handle == ResizeHandle.BottomLeft || handle == ResizeHandle.Bottom || handle == ResizeHandle.BottomRight

    var x = x
    var y = y
    var width = width
    var height = height
    if (movesLeft) {
        val dx = delta.x.coerceAtMost(width - NOTE_MIN_SIZE)
        x += dx
        width -= dx
    }
    if (movesRight) width = (width + delta.x).coerceAtLeast(NOTE_MIN_SIZE)
    if (movesTop) {
        val dy = delta.y.coerceAtMost(height - NOTE_MIN_SIZE)
        y += dy
        height -= dy
    }
    if (movesBottom) height = (height + delta.y).coerceAtLeast(NOTE_MIN_SIZE)
    return copy(x = x, y = y, width = width, height = height)
}

/** Type may be scaled this far by dragging a text element's box, and no further. */
private const val MIN_FONT_SCALE = 0.4f
private const val MAX_FONT_SCALE = 8f
