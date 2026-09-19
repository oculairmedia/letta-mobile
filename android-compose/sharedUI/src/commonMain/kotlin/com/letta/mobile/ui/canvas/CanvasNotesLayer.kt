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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.composables.icons.lucide.GripVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.X
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSceneDocument
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
    /** Dragging a selected card moves the whole selection; the host owns that gesture. */
    onGroupDrag: ((Offset) -> Unit)? = null,
    onGroupDragEnd: (() -> Unit)? = null,
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
                selection = NoteSelection(
                    selected = selected,
                    groupOffset = if (selected) groupOffset else Offset.Zero,
                    onPress = { shift -> onPress(document.id, shift) },
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
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var frame by remember(document.id) { mutableStateOf(document.frame ?: defaultFrame) }
    var gestureActive by remember(document.id) { mutableStateOf(false) }
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
        scope.launch { runCatching { session.moveDocument(document.id, committed) } }
    }

    // The card and its selection chrome share one placed, scaled box: the chrome draws OUTSIDE the
    // card's bounds (the Surface clips to its shape), and it has to follow a drag, so it cannot
    // live in the layer above.
    Box(
        modifier = Modifier
            .offset { IntOffset(screenTopLeft.x.roundToInt(), screenTopLeft.y.roundToInt()) }
            .size(width = widthDp, height = heightDp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = "Note ${document.id}" }
            // Taps and drags on the card belong to the note, never to the drawing beneath it; a
            // tap anywhere on it (the editor's own taps included, in the initial pass) makes it
            // the active note.
            .pointerInput(document.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    selection.onPress(currentEvent.keyboardModifiers.isShiftPressed)
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
        shadowElevation = when {
            plain && !active -> 0.dp
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
                cardColor = cardColor,
                onCard = onCard,
                onDragStart = { if (groupDrag == null) gestureActive = true },
                onDrag = onMove,
                onDragEnd = onMoveEnd,
                onExpand = onExpand,
                onRemove = { scope.launch { runCatching { session.removeDocument(document.id) } } },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (expanded) {
                    CanvasBlockPreview(
                        json = document.json,
                        onLightSurface = tint != null && !plain,
                        style = document.style,
                        modifier = Modifier.fillMaxSize().padding(start = if (plain) LettaDimens.Space.lg else LettaDimens.Space.md, end = LettaDimens.Space.md, top = LettaDimens.Space.xs, bottom = LettaDimens.Space.xs),
                    )
                } else {
                    CanvasBlockEditor(
                        session = session,
                        documentId = document.id,
                        storedJson = document.json,
                        active = active,
                        onLightSurface = tint != null && !plain,
                        onToolbar = onToolbar,
                        style = document.style,
                        modifier = Modifier.fillMaxSize().padding(start = if (plain) LettaDimens.Space.lg else LettaDimens.Space.md, end = LettaDimens.Space.md, top = LettaDimens.Space.xs, bottom = LettaDimens.Space.xs),
                    )
                }
                if (plain && active) {
                    TextMoveGrip(
                        modifier = Modifier.align(Alignment.TopStart),
                        onDragStart = { if (groupDrag == null) gestureActive = true },
                        onDrag = onMove,
                        onDragEnd = onMoveEnd,
                    )
                }
                NoteResizeHandle(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    onDragStart = { gestureActive = true },
                    onDrag = { delta ->
                        frame = frame.copy(
                            width = (frame.width + delta.x).coerceAtLeast(NOTE_MIN_SIZE),
                            height = (frame.height + delta.y).coerceAtLeast(NOTE_MIN_SIZE),
                        )
                    },
                    onDragEnd = ::commit,
                )
            }
        }
    }

        // The same chrome a selected shape gets, over the card and outside its bounds.
        if (active || selection.selected) {
            CanvasSelectionChrome(
                style = canvasSelectionStyle(),
                scale = scale,
                modifier = Modifier.matchParentSize(),
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

@Composable
private fun NoteResizeHandle(
    modifier: Modifier,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(LettaDimens.Control.icon)
            .dragHandle(onDragStart, onDrag, onDragEnd)
            .semantics { contentDescription = "Resize note" },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            modifier = Modifier
                .padding(LettaDimens.Space.xs)
                .size(LettaDimens.Space.sm)
                .clip(RoundedCornerShape(LettaDimens.Radius.sm))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        )
    }
}

/** Where the [index]th never-placed document lands: a stagger so several stay visible. */
internal fun defaultNoteFrame(index: Int): CanvasDocumentFrame = CanvasDocumentFrame(
    x = NOTE_DEFAULT_ORIGIN + index * NOTE_STAGGER,
    y = NOTE_DEFAULT_ORIGIN + index * NOTE_STAGGER,
    width = NOTE_DEFAULT_WIDTH,
    height = NOTE_DEFAULT_HEIGHT,
)

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
private const val NOTE_MIN_SIZE = 140f
private val NOTE_CORNER = LettaDimens.Radius.md
private val HANDLE_HEIGHT = LettaDimens.Control.iconButton
