package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
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

/**
 * Miro's quick-create targets: a small arrow button off each side of the selected element. One
 * press adds a matching, empty element that way, joined to this one by an arrow, and puts the
 * caret in it - so a chain of boxes is typed out without reaching for a tool.
 *
 * [anchor] is the element's screen rectangle; the buttons sit [OFFSET] beyond its edges, clear
 * of the selection handles. They are board chrome, so the pen cannot draw through them.
 */
@Composable
internal fun CanvasQuickCreateTargets(
    anchor: Rect,
    onCreate: (QuickCreateDirection) -> Unit,
    modifier: Modifier = Modifier,
    chromeRegions: CanvasChromeRegions? = null,
) {
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
                        .size(TARGET)
                        .canvasChrome(chromeRegions)
                        .semantics { contentDescription = "Add ${direction.label}" },
                ) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Icon(direction.icon, contentDescription = null, modifier = Modifier.size(ICON))
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val offset = OFFSET.roundToPx()
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val direction = QuickCreateDirection.entries[index]
                val centre = when (direction) {
                    QuickCreateDirection.UP -> Offset(anchor.center.x, anchor.top - offset)
                    QuickCreateDirection.RIGHT -> Offset(anchor.right + offset, anchor.center.y)
                    QuickCreateDirection.DOWN -> Offset(anchor.center.x, anchor.bottom + offset)
                    QuickCreateDirection.LEFT -> Offset(anchor.left - offset, anchor.center.y)
                }
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

    /** Draws an arrow from [start] to [end] and returns its id. */
    fun addArrow(controller: DrawBoxController, start: Offset, end: Offset): String? {
        val before = controller.state.value.elements.mapTo(HashSet()) { it.id }
        controller.onIntent(Intent.InsertNewShape(ShapeType.ARROW, start))
        controller.onIntent(Intent.UpdateLatestShape(end))
        return controller.state.value.elements.firstOrNull { it.id !in before }?.id
    }
}

private val TARGET = 26.dp
private val ICON = 14.dp
private val OFFSET = 34.dp
