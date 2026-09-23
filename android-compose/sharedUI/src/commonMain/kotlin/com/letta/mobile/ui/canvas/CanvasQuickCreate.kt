package com.letta.mobile.ui.canvas

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ArrowRight
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.canvas.CanvasDocumentFrame
import io.ak1.drawbox.domain.model.Element
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.domain.model.bounds
import io.ak1.drawbox.domain.model.translate
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Which side of an element a quick-create target sits on, and which way the new one goes. */
enum class QuickCreateDirection(val dx: Float, val dy: Float, val label: String, internal val icon: ImageVector) {
    UP(0f, -1f, "above", Lucide.ArrowUp),
    RIGHT(1f, 0f, "to the right", Lucide.ArrowRight),
    DOWN(0f, 1f, "below", Lucide.ArrowDown),
    LEFT(-1f, 0f, "to the left", Lucide.ArrowLeft),
}

/** An arrow being pulled out of a quick-create target, in board px: from the target to the pointer. */
internal data class QuickCreateDrag(val direction: QuickCreateDirection, val from: Offset, val to: Offset)

/**
 * Miro's quick-create targets: a small arrow button off each side of the selected element. One
 * press adds a matching, empty element that way, joined to this one by an arrow, and puts the
 * caret in it - so a chain of boxes is typed out without reaching for a tool. Dragged instead, a
 * target pulls an arrow out ([onDrag]), and where it is let go ([onDrop]) the new element goes.
 *
 * [anchor] is the element's screen rectangle; the buttons sit [OFFSET] beyond its edges, clear
 * of the selection handles. They are board chrome, so the pen cannot draw through them. On a
 * phone ([compact]) they are larger, big enough to hit with a finger.
 */
@Composable
internal fun CanvasQuickCreateTargets(
    anchor: Rect,
    onCreate: (QuickCreateDirection) -> Unit,
    modifier: Modifier = Modifier,
    chromeRegions: CanvasChromeRegions? = null,
    compact: Boolean = false,
    onDrag: (QuickCreateDrag?) -> Unit = {},
    onDrop: (QuickCreateDrag) -> Unit = {},
) {
    val target = if (compact) TOUCH_TARGET else TARGET
    val icon = if (compact) TOUCH_ICON else ICON
    val gapPx = with(LocalDensity.current) { (if (compact) TOUCH_OFFSET else OFFSET).toPx() }
    fun centreOf(direction: QuickCreateDirection): Offset = when (direction) {
        QuickCreateDirection.UP -> Offset(anchor.center.x, anchor.top - gapPx)
        QuickCreateDirection.RIGHT -> Offset(anchor.right + gapPx, anchor.center.y)
        QuickCreateDirection.DOWN -> Offset(anchor.center.x, anchor.bottom + gapPx)
        QuickCreateDirection.LEFT -> Offset(anchor.left - gapPx, anchor.center.y)
    }
    // The drag outlives recompositions (the anchor moves as the board pans), so it reads these fresh.
    val centre by rememberUpdatedState(::centreOf)
    val dragged by rememberUpdatedState(onDrag)
    val dropped by rememberUpdatedState(onDrop)
    Layout(
        modifier = modifier,
        content = {
            QuickCreateDirection.entries.forEach { direction ->
                Surface(
                    onClick = { onCreate(direction) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .size(target)
                        .canvasChrome(chromeRegions)
                        .semantics { contentDescription = "Add ${direction.label}" }
                        .pointerInput(direction) {
                            var drag: QuickCreateDrag? = null
                            detectDragGestures(
                                onDragStart = { local ->
                                    val from = centre(direction)
                                    drag = QuickCreateDrag(direction, from, from - Offset(size.width / 2f, size.height / 2f) + local)
                                    dragged(drag)
                                },
                                onDragEnd = {
                                    drag?.let(dropped)
                                    drag = null
                                    dragged(null)
                                },
                                onDragCancel = {
                                    drag = null
                                    dragged(null)
                                },
                            ) { change, amount ->
                                change.consume()
                                drag = drag?.let { it.copy(to = it.to + amount) }
                                dragged(drag)
                            }
                        },
                ) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Icon(direction.icon, contentDescription = null, modifier = Modifier.size(icon))
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val centre = centreOf(QuickCreateDirection.entries[index])
                placeable.place((centre.x - placeable.width / 2f).roundToInt(), (centre.y - placeable.height / 2f).roundToInt())
            }
        }
    }
}

/** Where quick-created elements go and how they are joined. */
internal object CanvasQuickCreate {
    /** Space, in board units, between an element and the one quick-created beside it. */
    const val GAP = 80f

    /** An empty copy of [shape] one gap beyond it towards [direction], above everything else. */
    @OptIn(ExperimentalTime::class)
    fun nextShape(shape: Element.Shape, direction: QuickCreateDirection, topZ: Int): Element.Shape {
        val b = shape.bounds()
        val delta = Offset(direction.dx * (b.width + GAP), direction.dy * (b.height + GAP))
        val now = Clock.System.now().toEpochMilliseconds()
        return (shape.translate(delta) as Element.Shape).copy(
            id = "${shape.id}-next-$now",
            text = "",
            startBinding = null,
            endBinding = null,
            zIndex = topZ + 1,
            createdAt = now,
            modifiedAt = now,
        )
    }

    /** A note frame one gap beyond [frame] towards [direction]. */
    fun nextFrame(frame: CanvasDocumentFrame, direction: QuickCreateDirection): CanvasDocumentFrame = frame.copy(
        x = frame.x + direction.dx * (frame.width + GAP),
        y = frame.y + direction.dy * (frame.height + GAP),
    )

    /** The arrow from [from]'s side facing [direction] to the facing side of [to]. */
    fun connector(from: Rect, to: Rect, direction: QuickCreateDirection): Pair<Offset, Offset> = when (direction) {
        QuickCreateDirection.UP -> Offset(from.center.x, from.top) to Offset(to.center.x, to.bottom)
        QuickCreateDirection.RIGHT -> Offset(from.right, from.center.y) to Offset(to.left, to.center.y)
        QuickCreateDirection.DOWN -> Offset(from.center.x, from.bottom) to Offset(to.center.x, to.top)
        QuickCreateDirection.LEFT -> Offset(from.left, from.center.y) to Offset(to.right, to.center.y)
    }

    /** Which side of [from] faces [point]: the one along the axis it is furthest out on. */
    fun directionToward(from: Rect, point: Offset): QuickCreateDirection {
        val d = point - from.center
        return if (kotlin.math.abs(d.x) >= kotlin.math.abs(d.y)) {
            if (d.x >= 0f) QuickCreateDirection.RIGHT else QuickCreateDirection.LEFT
        } else {
            if (d.y >= 0f) QuickCreateDirection.DOWN else QuickCreateDirection.UP
        }
    }

    /**
     * An empty shape of [kind] centred on [centre], sized and styled like [base], above everything
     * else: what goes where an arrow pulled out of a target is let go.
     */
    @OptIn(ExperimentalTime::class)
    fun shapeAt(base: Element.Shape, centre: Offset, kind: QuickCreateKind, topZ: Int): Element.Shape {
        val now = Clock.System.now().toEpochMilliseconds()
        val moved = (base.translate(centre - base.bounds().center) as Element.Shape).copy(
            id = "${base.id}-next-$now",
            text = "",
            startBinding = null,
            endBinding = null,
            zIndex = topZ + 1,
            createdAt = now,
            modifiedAt = now,
        )
        val b = moved.bounds()
        return when (kind) {
            QuickCreateKind.RECTANGLE -> CanvasReshape.reshaped(moved, ShapeType.RECTANGLE).copy(cornerRadius = 0f)
            QuickCreateKind.ROUNDED -> CanvasReshape.reshaped(moved, ShapeType.RECTANGLE)
                .copy(cornerRadius = minOf(b.width, b.height) * ROUNDED_CORNER)
            QuickCreateKind.CIRCLE -> CanvasReshape.reshaped(moved, ShapeType.CIRCLE)
            QuickCreateKind.TRIANGLE -> CanvasReshape.reshaped(moved, ShapeType.TRIANGLE)
            QuickCreateKind.SAME, QuickCreateKind.NOTE, QuickCreateKind.TEXT -> moved
        }
    }

    /** A plain box to size new shapes from when the arrow comes out of a note rather than a shape. */
    fun defaultShape(strokeColor: Color, strokeWidth: Float): Element.Shape = Element.Shape(
        id = "shape",
        shapeType = ShapeType.RECTANGLE,
        points = listOf(Offset.Zero, Offset(DEFAULT_WIDTH, DEFAULT_HEIGHT)),
        strokeColor = strokeColor,
        strokeWidth = strokeWidth,
    )

    /** Draws an arrow from [start] to [end] and returns its id. */
    fun addArrow(controller: DrawBoxController, start: Offset, end: Offset): String? {
        val before = controller.state.value.elements.mapTo(HashSet()) { it.id }
        controller.onIntent(Intent.InsertNewShape(ShapeType.ARROW, start))
        controller.onIntent(Intent.UpdateLatestShape(end))
        return controller.state.value.elements.firstOrNull { it.id !in before }?.id
    }
}

/** What an arrow pulled out of a target can make where it is let go, in its menu's order. */
enum class QuickCreateKind(val label: String) {
    SAME("Same as this"),
    RECTANGLE("Rectangle"),
    ROUNDED("Rounded rectangle"),
    CIRCLE("Circle"),
    TRIANGLE("Triangle"),
    NOTE("Note"),
    TEXT("Text"),
}

private const val ROUNDED_CORNER = 0.2f
private const val DEFAULT_WIDTH = 160f
private const val DEFAULT_HEIGHT = 100f

private val TARGET = 26.dp
private val ICON = 14.dp
private val OFFSET = 34.dp
// A finger's size: Material's touch minimum is 48dp, and the disc sits a little inside that.
private val TOUCH_TARGET = 40.dp
private val TOUCH_ICON = 20.dp
private val TOUCH_OFFSET = 46.dp
