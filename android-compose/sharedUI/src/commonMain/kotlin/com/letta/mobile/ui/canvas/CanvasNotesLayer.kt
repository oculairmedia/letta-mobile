package com.letta.mobile.ui.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import com.composables.icons.lucide.X
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.CanvasSceneDocument
import com.letta.mobile.data.canvas.CanvasSession
import io.ak1.drawbox.domain.model.Viewport
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The canvas's block documents as elements on the board, one card per document, placed in the
 * drawing's world coordinates and moving and scaling with its [viewport] so they sit among the
 * strokes like sticky notes rather than in a separate mode.
 *
 * Each card is dragged by its handle bar and resized from its corner; the frame is written to the
 * session when the gesture ends, so peers and the agent see the move as one op. A document that
 * was never placed gets a staggered default spot until someone moves it.
 */
@Composable
fun CanvasNotesLayer(
    session: CanvasSession,
    documents: List<CanvasSceneDocument>,
    viewport: Viewport,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        documents.forEachIndexed { index, document ->
            CanvasNoteCard(
                session = session,
                document = document,
                viewport = viewport,
                defaultFrame = defaultNoteFrame(index),
            )
        }
    }
}

@Composable
private fun CanvasNoteCard(
    session: CanvasSession,
    document: CanvasSceneDocument,
    viewport: Viewport,
    defaultFrame: CanvasDocumentFrame,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var frame by remember(document.id) { mutableStateOf(document.frame ?: defaultFrame) }
    var gestureActive by remember(document.id) { mutableStateOf(false) }
    LaunchedEffect(document.frame) {
        if (!gestureActive) frame = document.frame ?: defaultFrame
    }

    val screenTopLeft = viewport.worldToScreen(Offset(frame.x, frame.y))
    val scale = viewport.scale
    val tint = parseHexColor(document.color)
    val cardColor = tint ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val onCard = if (tint != null) contrastOn(tint) else MaterialTheme.colorScheme.onSurfaceVariant
    val widthDp = with(density) { frame.width.toDp() }
    val heightDp = with(density) { frame.height.toDp() }

    // Drag deltas arrive in the card's own (unscaled) space because the scale is a graphics-layer
    // transform, so they are already world units.
    fun commit() {
        gestureActive = false
        val committed = frame
        scope.launch { runCatching { session.moveDocument(document.id, committed) } }
    }

    Surface(
        modifier = Modifier
            .offset { IntOffset(screenTopLeft.x.roundToInt(), screenTopLeft.y.roundToInt()) }
            .size(width = widthDp, height = heightDp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .semantics { contentDescription = "Note ${document.id}" }
            // Taps and drags on the card belong to the note, never to the drawing beneath it.
            .pointerInput(document.id) { detectTapGestures(onTap = {}) },
        shape = RoundedCornerShape(NOTE_CORNER),
        color = cardColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NoteHandleBar(
                cardColor = cardColor,
                onCard = onCard,
                onDragStart = { gestureActive = true },
                onDrag = { delta -> frame = frame.copy(x = frame.x + delta.x, y = frame.y + delta.y) },
                onDragEnd = ::commit,
                onRecolor = { color -> scope.launch { runCatching { session.recolorDocument(document.id, color.hex) } } },
                onRemove = { scope.launch { runCatching { session.removeDocument(document.id) } } },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CanvasBlockEditor(
                    session = session,
                    documentId = document.id,
                    storedJson = document.json,
                    onLightSurface = tint != null,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
                )
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
}

@Composable
private fun NoteHandleBar(
    cardColor: Color,
    onCard: Color,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onRecolor: (NamedColor) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HANDLE_HEIGHT)
            .background(onCard.copy(alpha = 0.08f))
            .pointerInput(Unit) {
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
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.GripVertical,
            contentDescription = "Move note",
            modifier = Modifier.size(14.dp),
            tint = onCard,
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = "Note",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = onCard,
            modifier = Modifier.weight(1f),
        )
        ColorSwatchPicker(
            current = cardColor,
            palette = NoteColors,
            label = "Note color",
            onPick = { picked -> NoteColors.firstOrNull { it.color == picked }?.let(onRecolor) },
            swatchSize = 16.dp,
            modifier = Modifier.size(HANDLE_HEIGHT),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(HANDLE_HEIGHT)) {
            Icon(
                imageVector = Lucide.X,
                contentDescription = "Remove note",
                modifier = Modifier.size(14.dp),
                tint = onCard,
            )
        }
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
            .size(18.dp)
            .pointerInput(Unit) {
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
            .semantics { contentDescription = "Resize note" },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            modifier = Modifier
                .padding(4.dp)
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
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

internal const val NOTE_DEFAULT_WIDTH = 320f
internal const val NOTE_DEFAULT_HEIGHT = 240f
private const val NOTE_DEFAULT_ORIGIN = 80f
private const val NOTE_STAGGER = 40f
private const val NOTE_MIN_SIZE = 140f
private val NOTE_CORNER = 12.dp
private val HANDLE_HEIGHT = 28.dp
