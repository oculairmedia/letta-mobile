package com.letta.mobile.ui.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.host.LocalRowHoldDrag
import com.letta.mobile.ui.host.RowDragTracker
import com.letta.mobile.ui.host.RowHoldDrag
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellRailEntry

/**
 * The secondary actions of the shell's rows, built once for every gesture that opens them: the
 * desktop's right-click menu and the touch long-press menu both receive these lists through
 * [com.letta.mobile.ui.host.LettaHost.rowMenu], so a row's actions are defined in one place.
 */
object ShellRowMenus {
    /**
     * A conversation: rename and pin or unpin when the host offers them (letta-mobile-bzvro.17),
     * archive or restore, and delete (the row asks before it deletes). Where delete only moves a
     * chat to Archived, an archived chat has nothing left to delete, so it offers no Delete.
     */
    fun conversation(
        archived: Boolean,
        deleting: Boolean,
        actions: ShellConversationMenuActions,
        deleteBehavior: ConversationDeleteBehavior = ConversationDeleteBehavior.Permanent,
    ): List<LettaMenuItem> =
        if (deleting) {
            emptyList()
        } else {
            val offersDelete = !(archived && deleteBehavior == ConversationDeleteBehavior.MovesToArchived)
            actions.manage.items() + listOfNotNull(
                LettaMenuItem(if (archived) "Restore chat" else "Archive chat", onClick = actions.onArchiveToggle),
                LettaMenuItem("Delete chat", onClick = actions.onRequestDelete).takeIf { offersDelete },
            )
        }

    /** A canvas: archive or restore, when the host can archive canvases (null: it cannot). */
    fun canvas(archived: Boolean, onArchiveToggle: (() -> Unit)?): List<LettaMenuItem> =
        listOfNotNull(
            onArchiveToggle?.let { LettaMenuItem(if (archived) "Restore canvas" else "Archive canvas", onClick = it) },
        )

    /** A rail agent: open it, pin or unpin it, open its settings (each when the host offers it). */
    fun agent(entry: ShellRailEntry, actions: ShellAgentRailActions): List<LettaMenuItem> =
        listOfNotNull(
            LettaMenuItem("Open") { actions.onAgentSelected(entry.agentId) },
            actions.onAgentPinnedChange?.let { onPinned ->
                LettaMenuItem(if (entry.pinned) "Unpin agent" else "Pin agent") { onPinned(entry.agentId, !entry.pinned) }
            },
            actions.onAgentSettings?.let { onSettings -> LettaMenuItem("Agent settings") { onSettings(entry.agentId) } },
        )
}

/** A conversation row's secondary actions (archive, delete, optional rename/pin manage menu). */
data class ShellConversationMenuActions(
    val onArchiveToggle: () -> Unit,
    val onRequestDelete: () -> Unit,
    val manage: ShellConversationManageMenu = ShellConversationManageMenu.None,
)

/**
 * A conversation row's manage entries: rename and pin (letta-mobile-bzvro.17), settle / move to
 * active and the marker (letta-mobile-zxk2n.5). A null action is one the host does not offer.
 */
data class ShellConversationManageMenu(
    val pinned: Boolean = false,
    val onRenameRequest: (() -> Unit)? = null,
    val onPinToggle: (() -> Unit)? = null,
    /** letta-mobile-7mys7 (G37): fork the chat, asking for the fork's name first. */
    val onForkRequest: (() -> Unit)? = null,
    val settled: Boolean = false,
    val onSettleToggle: (() -> Unit)? = null,
    val onMarkerRequest: (() -> Unit)? = null,
) {
    fun items(): List<LettaMenuItem> = listOfNotNull(
        onRenameRequest?.let { LettaMenuItem("Rename chat", onClick = it) },
        onPinToggle?.let { LettaMenuItem(if (pinned) "Unpin chat" else "Pin chat", onClick = it) },
        onForkRequest?.let { LettaMenuItem("Fork chat…", onClick = it) },
        onMarkerRequest?.let { LettaMenuItem("Set marker…", onClick = it) },
        onSettleToggle?.let { LettaMenuItem(if (settled) "Move to active" else "Settle chat", onClick = it) },
    )

    companion object {
        val None = ShellConversationManageMenu()
    }
}

/** Test tags for the touch row menu. */
object ShellRowMenuTags {
    const val MENU = "shell-row-menu"
}

/**
 * The touch row menu, [com.letta.mobile.ui.host.LettaHost.rowMenu]'s default: a long-press on [content] (with a
 * haptic tick) opens the [items] as a popup anchored to the row. A plain tap still reaches the row.
 * No items, no gesture. When the row can be dragged ([LocalRowHoldDrag], a reordering RosterList),
 * the hold only lifts the row (the tick): moving the finger on drags it, and letting go without
 * moving opens the menu. The menu waits for the release so it never covers, or takes, the drag.
 */
@Composable
fun ShellLongPressMenu(items: List<LettaMenuItem>, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val hold = LocalRowHoldDrag.current
    val openMenu = remember { { open = true } }
    val onLongPress = remember(haptics, hold) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (hold == null) open = true
        }
    }
    val press = ShellHold(onLongPress, hold, onHeldRelease = openMenu)
    Box(Modifier.shellLongPress(enabled = items.isNotEmpty(), press = press)) {
        // The drag is this row's alone: a row menu nested in the content does not drag it too.
        CompositionLocalProvider(LocalRowHoldDrag provides null) { content() }
        LettaPopupMenu(
            expanded = open && items.isNotEmpty(),
            onDismiss = { open = false },
            items = items,
            modifier = Modifier.testTag(ShellRowMenuTags.MENU),
        )
    }
}

/**
 * What a held row does: [onLongPress] when the hold registers; with a [hold] drag, a hold that then
 * travels drags through it, and one let go without moving calls [onHeldRelease].
 */
data class ShellHold(
    val onLongPress: () -> Unit,
    val hold: RowHoldDrag? = null,
    val onHeldRelease: () -> Unit = {},
)

/** [shellLongPress] for a hold with no drag. */
fun Modifier.shellLongPress(enabled: Boolean, onLongPress: () -> Unit): Modifier =
    shellLongPress(enabled, ShellHold(onLongPress))

/**
 * Calls [ShellHold.onLongPress] when a pointer is held still for the long-press timeout. It watches
 * the Initial pass, so it sees the press before a clickable child takes it; once it fires it consumes
 * the rest of the gesture, so the child's click does not also land. A tap, or a drag past the touch
 * slop (a scroll), leaves everything to the child. With a [ShellHold.hold], a held pointer that then
 * travels past the slop drags: [RowHoldDrag.onStart], each vertical step, then [RowHoldDrag.onEnd].
 */
fun Modifier.shellLongPress(enabled: Boolean, press: ShellHold): Modifier =
    if (!enabled) {
        this
    } else {
        this.then(
            Modifier.pointerInput(press) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        awaitReleaseOrSlop(down, viewConfiguration.touchSlop)
                    }
                    if (released == null) {
                        press.onLongPress()
                        val hold = press.hold
                        when {
                            hold == null -> consumeUntilAllUp()
                            !followHold(down, viewConfiguration.touchSlop, hold) -> press.onHeldRelease()
                        }
                    }
                }
            },
        )
    }

/** After the long-press: consumes the gesture, dragging through [hold] once it moves past [slop]. True when it dragged. */
private suspend fun AwaitPointerEventScope.followHold(down: PointerInputChange, slop: Float, hold: RowHoldDrag): Boolean {
    val tracker = RowDragTracker(down.position, slop, hold)
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.firstOrNull { it.id == down.id && it.pressed }?.let(tracker::follow)
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
    return tracker.finish()
}

/** Waits, without consuming, until [down]'s pointer lifts or moves past [slop]. */
private suspend fun AwaitPointerEventScope.awaitReleaseOrSlop(down: PointerInputChange, slop: Float) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return
        if (endsTheHold(change, down, slop)) return
    }
}

/** A hold ends when the pointer lifts, something else takes it, or it travels past [slop] (a scroll). */
private fun endsTheHold(change: PointerInputChange, down: PointerInputChange, slop: Float): Boolean =
    !change.pressed || change.isConsumed || (change.position - down.position).getDistance() > slop

private suspend fun AwaitPointerEventScope.consumeUntilAllUp() {
    do {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}
