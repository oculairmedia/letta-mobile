package com.letta.mobile.ui.canvas

import com.letta.mobile.ui.host.LettaMenu
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import com.letta.mobile.ui.components.foundation.LettaDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import io.ak1.drawbox.domain.model.Intent
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.domain.model.ShapeType
import io.ak1.drawbox.presentation.viewmodel.DrawBoxController
import kotlin.math.roundToInt
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.components.foundation.LettaMenuEntry
import com.letta.mobile.ui.components.foundation.LettaMenuItem

/** Where a context menu was asked for, and whether it landed on something. */
internal data class BoardMenuRequest(
    /** In the board's own (screen) space, where the menu opens. */
    val screen: Offset,
    /** The same point on the board, where anything added from the menu goes. */
    val world: Offset,
    /** True when the press selected an element: the menu is that element's. */
    val onElement: Boolean,
    /** True when that element holds text (a closed shape or a text element). */
    val canEditText: Boolean,
)

/** What the board can add at a point: the menu lists these, the board does them. */
internal class BoardInsertActions(
    val onAddNote: ((Offset) -> Unit)?,
    val onAddText: (Offset) -> Unit,
    val onAddShape: (Mode, Offset) -> Unit,
    /** Opens the image picker; what is picked goes on the board at the point. */
    val onAddImages: ((Offset) -> Unit)? = null,
)

/** What the board can do to the element a menu was opened on. */
internal class BoardElementActions(
    val onEditText: () -> Unit,
    val onDuplicate: () -> Unit,
    val onBringToFront: () -> Unit,
    val onSendToBack: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * The board's context menu, opened by a long press (or a right click) where the finger was, the
 * way Obsidian's canvas does it: on empty board it adds things there - a note, text, a shape - and
 * on an element it offers what applies to that element. It is what lets the phone's tool bar keep
 * only the tools a hand switches between constantly.
 */
@Composable
internal fun CanvasBoardMenu(
    request: BoardMenuRequest,
    insert: BoardInsertActions,
    element: BoardElementActions,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.offsetTo(request.screen)) {
        LettaMenu(expanded = true, onDismiss = onDismiss) {
            if (request.onElement) {
                if (request.canEditText) MenuItem(LettaIcons.TextCursorInput, "Edit text", onDismiss, element.onEditText)
                MenuItem(LettaIcons.Copy, "Duplicate", onDismiss, element.onDuplicate)
                MenuItem(LettaIcons.BringToFront, "Bring to front", onDismiss, element.onBringToFront)
                MenuItem(LettaIcons.SendToBack, "Send to back", onDismiss, element.onSendToBack)
                LettaDivider()
                MenuItem(LettaIcons.Delete, "Delete", onDismiss, element.onDelete)
            } else {
                CanvasInsertMenuItems(insert, at = request.world, onDismiss = onDismiss)
            }
        }
    }
}

/** The "add" entries, shared by the context menu and the phone bar's add button. */
@Composable
internal fun CanvasInsertMenuItems(insert: BoardInsertActions, at: Offset, onDismiss: () -> Unit) {
    insert.onAddNote?.let { addNote -> MenuItem(LettaIcons.StickyNote, "Note", onDismiss) { addNote(at) } }
    MenuItem(LettaIcons.Type, "Text", onDismiss) { insert.onAddText(at) }
    insert.onAddImages?.let { addImages -> MenuItem(LettaIcons.Image, "Image", onDismiss) { addImages(at) } }
    LettaDivider()
    CanvasInsert.ShapeModes.forEach { (mode, label) ->
        MenuItem(iconFor(mode), label, onDismiss) { insert.onAddShape(mode, at) }
    }
}

/** One of the board menu's rows: the shared menu row. */
@Composable
private fun MenuItem(icon: ImageVector, label: String, onDismiss: () -> Unit, onClick: () -> Unit) {
    LettaMenuEntry(LettaMenuItem(label, icon, onClick = onClick), onDismiss)
}

private fun Modifier.offsetTo(position: Offset): Modifier =
    offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }

/** Putting shapes on the board at a point, as the menus do, rather than by dragging one out. */
internal object CanvasInsert {

    /** The shapes the menus offer, in menu order. */
    val ShapeModes: List<Pair<Mode, String>> = listOf(
        Mode.RECTANGLE to "Rectangle",
        Mode.CIRCLE to "Circle",
        Mode.TRIANGLE to "Triangle",
        Mode.LINE to "Line",
        Mode.ARROW to "Arrow",
    )

    fun shapeTypeFor(mode: Mode): ShapeType? = when (mode) {
        Mode.RECTANGLE -> ShapeType.RECTANGLE
        Mode.CIRCLE -> ShapeType.CIRCLE
        Mode.TRIANGLE -> ShapeType.TRIANGLE
        Mode.LINE -> ShapeType.LINE
        Mode.ARROW -> ShapeType.ARROW
        else -> null
    }

    /**
     * Adds a [mode] shape centred on [world], at a size that reads at 100% and can be typed into,
     * the way DrawBox builds one from a drag: an insert at one corner, then the opposite corner.
     * Returns the new shape's id, or null when [mode] is not a shape.
     */
    fun addShape(controller: DrawBoxController, mode: Mode, world: Offset): String? {
        val type = shapeTypeFor(mode) ?: return null
        val half = when (type) {
            ShapeType.LINE, ShapeType.ARROW -> Offset(DEFAULT_WIDTH / 2f, 0f)
            ShapeType.CIRCLE, ShapeType.TRIANGLE -> Offset(DEFAULT_HEIGHT / 2f, DEFAULT_HEIGHT / 2f)
            else -> Offset(DEFAULT_WIDTH / 2f, DEFAULT_HEIGHT / 2f)
        }
        val before = controller.state.value.elements.map { it.id }.toSet()
        controller.onIntent(Intent.InsertNewShape(type, world - half))
        controller.onIntent(Intent.UpdateLatestShape(world + half))
        return controller.state.value.elements.firstOrNull { it.id !in before }?.id
    }

    private const val DEFAULT_WIDTH = 200f
    private const val DEFAULT_HEIGHT = 120f
}
