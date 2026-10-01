package com.letta.mobile.ui.chat.surface

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.chat_surface_dock_collapse
import com.letta.mobile.sharedui.resources.chat_surface_dock_grow
import com.letta.mobile.sharedui.resources.chat_surface_dock_move_down
import com.letta.mobile.sharedui.resources.chat_surface_dock_move_left
import com.letta.mobile.sharedui.resources.chat_surface_dock_move_right
import com.letta.mobile.sharedui.resources.chat_surface_dock_move_up
import com.letta.mobile.sharedui.resources.chat_surface_dock_panel
import com.letta.mobile.sharedui.resources.chat_surface_dock_reset
import com.letta.mobile.sharedui.resources.chat_surface_dock_restore
import com.letta.mobile.sharedui.resources.chat_surface_dock_shrink
import com.letta.mobile.ui.chat.session.ChatDockEdge
import com.letta.mobile.ui.theme.ChatSurfaceDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1: the docked panel's accessibility actions. Dragging and resizing are
 * pointer gestures; these give keyboard and screen-reader users the same placement controls:
 * move by a step, grow or shrink, reset, and collapse or restore.
 */
@Immutable
internal class DockSemanticsLabels(
    val panel: String,
    val moveUp: String,
    val moveDown: String,
    val moveLeft: String,
    val moveRight: String,
    val grow: String,
    val shrink: String,
    val reset: String,
    val collapse: String,
    val restore: String,
)

@Composable
internal fun dockSemanticsLabels(): DockSemanticsLabels = DockSemanticsLabels(
    panel = stringResource(Res.string.chat_surface_dock_panel),
    moveUp = stringResource(Res.string.chat_surface_dock_move_up),
    moveDown = stringResource(Res.string.chat_surface_dock_move_down),
    moveLeft = stringResource(Res.string.chat_surface_dock_move_left),
    moveRight = stringResource(Res.string.chat_surface_dock_move_right),
    grow = stringResource(Res.string.chat_surface_dock_grow),
    shrink = stringResource(Res.string.chat_surface_dock_shrink),
    reset = stringResource(Res.string.chat_surface_dock_reset),
    collapse = stringResource(Res.string.chat_surface_dock_collapse),
    restore = stringResource(Res.string.chat_surface_dock_restore),
)

/** The panel's description and its placement actions; each moves or resizes by one step. */
internal fun Modifier.dockSemantics(state: ChatDockState, labels: DockSemanticsLabels): Modifier = semantics {
    val step = ChatSurfaceDimens.dockAccessibilityStep.value
    contentDescription = labels.panel
    customActions = listOf(
        dockAction(labels.moveUp) { state.drag(0f, -step) },
        dockAction(labels.moveDown) { state.drag(0f, step) },
        dockAction(labels.moveLeft) { state.drag(-step, 0f) },
        dockAction(labels.moveRight) { state.drag(step, 0f) },
        // The top-right corner: the panel grows up and out from where it sits.
        dockAction(labels.grow) { state.resize(ChatDockEdge.TopRight, step, -step) },
        dockAction(labels.shrink) { state.resize(ChatDockEdge.TopRight, -step, step) },
        dockAction(labels.reset) { state.reset() },
        dockAction(if (state.geometry.collapsed) labels.restore else labels.collapse) { state.toggleCollapsed() },
    )
}

private fun dockAction(label: String, run: () -> Unit) = CustomAccessibilityAction(label) {
    run()
    true
}
