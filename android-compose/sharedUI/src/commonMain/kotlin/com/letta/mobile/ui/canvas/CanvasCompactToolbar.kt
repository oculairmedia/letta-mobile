package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Redo2
import com.composables.icons.lucide.Undo2
import com.letta.mobile.data.canvas.CanvasSyncHealth
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.canvas_foot_more
import com.letta.mobile.sharedui.resources.canvas_foot_more_status
import com.letta.mobile.sharedui.resources.canvas_foot_redo
import com.letta.mobile.sharedui.resources.canvas_foot_undo
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource
import io.ak1.drawbox.domain.model.Mode
import io.ak1.drawbox.ui.controls.ControlsBarIntent
import io.ak1.drawbox.ui.controls.ControlsBarState

/**
 * The phone board's tools: one pill bar along the bottom edge instead of the rail down the left.
 *
 * It keeps only the tools a hand switches between constantly - select, draw, eraser - plus one
 * add button and the property control. Everything that puts something on the board (notes, text,
 * every shape) is in the add menu, which is the same menu a long press opens on the board itself,
 * where it adds at the finger instead of the middle of the screen. Undo and redo are not here:
 * [CanvasActionsPill] carries them in the compact layout - except under the phone's chat page,
 * which keeps the top of the board clear, where they end the bar with the overflow menu
 * ([CompactBoardActions]).
 *
 * There is no pan tool: on a phone a finger dragged across open board pans, and two fingers pinch
 * (see [touchNavigation]).
 *
 * The property control opens its panel above the bar, since beside a bottom bar is off screen.
 */
/** What the phone bar's buttons do. */
internal class CompactToolbarActions(
    val dispatch: (ControlsBarIntent) -> Unit,
    val dispatchProperty: (CanvasPropertyIntent) -> Unit,
    val insert: BoardInsertActions,
    /** Where the add button puts things: the middle of what is on screen, in board coordinates. */
    val addAt: () -> Offset,
)

@Composable
internal fun CanvasCompactToolbar(
    state: ControlsBarState,
    properties: CanvasProperties,
    actions: CompactToolbarActions,
    modifier: Modifier = Modifier,
    /** The board's own actions at the end of the bar, after a divider: see [CompactBoardActions]. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val dispatch = actions.dispatch
    Surface(
        modifier = modifier.testTag(CANVAS_COMPACT_TOOLBAR_TAG),
        // A full pill, the shape Craft, Freeform and Obsidian's canvas all float their tools in.
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = LettaDimens.Space.hair,
        shadowElevation = LettaDimens.Space.sm,
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactPrimaryModes.forEach { (mode, label) ->
                ControlButton(Control(iconFor(mode), label, selected = state.currentMode == mode), size = COMPACT_BUTTON) {
                    dispatch(ControlsBarIntent.SelectMode(mode))
                }
            }
            AddButton(actions.insert, actions.addAt)
            CanvasPropertyControl(
                state = state,
                properties = properties,
                dispatch = dispatch,
                dispatchProperty = actions.dispatchProperty,
                label = "Stroke color",
                placement = PropertyPopoverPlacement.ABOVE,
                modifier = Modifier.size(COMPACT_BUTTON),
            )
            if (trailing != null) {
                ToolbarDivider()
                trailing()
            }
        }
    }
}

/**
 * Undo, redo and the overflow menu at the end of the phone bar, where the board's actions go when
 * the page around it keeps the top of the board clear (see [CanvasHostChrome]). Sharing and the
 * sync status are in the menu; a status other than "synced" also marks the more button with its
 * dot, so a board that stopped syncing never looks like one that syncs.
 */
@Composable
internal fun CompactBoardActions(undo: CanvasUndoActions, overflow: CanvasOverflow) {
    ControlButton(
        Control(Lucide.Undo2, stringResource(Res.string.canvas_foot_undo), enabled = undo.canUndo),
        size = COMPACT_BUTTON,
        onClick = undo.onUndo,
    )
    ControlButton(
        Control(Lucide.Redo2, stringResource(Res.string.canvas_foot_redo), enabled = undo.canRedo),
        size = COMPACT_BUTTON,
        onClick = undo.onRedo,
    )
    var open by remember { mutableStateOf(false) }
    val sync = overflow.sync?.takeIf { it != CanvasSyncHealth.Synced }
    val label = if (sync == null) {
        stringResource(Res.string.canvas_foot_more)
    } else {
        stringResource(Res.string.canvas_foot_more_status, canvasSyncStatusText(sync).label)
    }
    Box(Modifier.testTag(CANVAS_FOOT_MORE_TAG)) {
        ControlButton(Control(Lucide.EllipsisVertical, label), size = COMPACT_BUTTON) { open = true }
        if (sync != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(LettaDimens.Space.xs)
                    .size(SYNC_DOT)
                    .background(canvasSyncDotColor(sync), CircleShape),
            )
        }
        CanvasOverflowMenu(overflow, expanded = open, onDismiss = { open = false })
    }
}

@Composable
private fun ToolbarDivider() {
    Box(
        Modifier
            .padding(horizontal = LettaDimens.Space.xs)
            .size(width = LettaDimens.Stroke.hairline, height = LettaDimens.Space.xl)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** The bar's one way to put things on the board; the same entries a long press offers. */
@Composable
private fun AddButton(insert: BoardInsertActions, addAt: () -> Offset) {
    var open by remember { mutableStateOf(false) }
    Box {
        ControlButton(Control(Lucide.Plus, "Add"), size = COMPACT_BUTTON) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CanvasInsertMenuItems(insert, at = addAt(), onDismiss = { open = false })
        }
    }
}

/** The tools that keep a button of their own on the phone bar, in bar order. */
internal val CompactPrimaryModes: List<Pair<Mode, String>> =
    CanvasModes.filter { (mode, _) -> mode in setOf(Mode.SELECT, Mode.PEN, Mode.ERASER) }

/** Big enough for a fingertip and small enough that the bar fits a 360dp phone. */
private val COMPACT_BUTTON = 40.dp
