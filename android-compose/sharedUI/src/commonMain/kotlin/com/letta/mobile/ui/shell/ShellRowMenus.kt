package com.letta.mobile.ui.shell

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
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
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.shell.rail.ShellAgentRailActions
import com.letta.mobile.ui.shell.rail.ShellRailEntry

/**
 * The secondary actions of the shell's rows, built once for every gesture that opens them: the
 * desktop's right-click menu and the touch long-press menu both receive these lists through
 * [ShellChromeDecorations.rowMenu], so a row's actions are defined in one place.
 */
object ShellRowMenus {
    /**
     * A conversation: rename and pin or unpin when the host offers them (letta-mobile-bzvro.17),
     * archive or restore, and delete (the row asks before it deletes).
     */
    fun conversation(
        archived: Boolean,
        deleting: Boolean,
        actions: ShellConversationMenuActions,
    ): List<ShellRowMenuItem> =
        if (deleting) {
            emptyList()
        } else {
            actions.manage.items() + listOf(
                ShellRowMenuItem(if (archived) "Restore chat" else "Archive chat", actions.onArchiveToggle),
                ShellRowMenuItem("Delete chat", actions.onRequestDelete),
            )
        }

    /** A canvas: archive or restore, when the host can archive canvases (null: it cannot). */
    fun canvas(archived: Boolean, onArchiveToggle: (() -> Unit)?): List<ShellRowMenuItem> =
        listOfNotNull(
            onArchiveToggle?.let { ShellRowMenuItem(if (archived) "Restore canvas" else "Archive canvas", it) },
        )

    /** A rail agent: open it, pin or unpin it, open its settings (each when the host offers it). */
    fun agent(entry: ShellRailEntry, actions: ShellAgentRailActions): List<ShellRowMenuItem> =
        listOfNotNull(
            ShellRowMenuItem("Open") { actions.onAgentSelected(entry.agentId) },
            actions.onAgentPinnedChange?.let { onPinned ->
                ShellRowMenuItem(if (entry.pinned) "Unpin agent" else "Pin agent") { onPinned(entry.agentId, !entry.pinned) }
            },
            actions.onAgentSettings?.let { onSettings -> ShellRowMenuItem("Agent settings") { onSettings(entry.agentId) } },
        )
}

/** A conversation row's secondary actions (archive, delete, optional rename/pin manage menu). */
data class ShellConversationMenuActions(
    val onArchiveToggle: () -> Unit,
    val onRequestDelete: () -> Unit,
    val manage: ShellConversationManageMenu = ShellConversationManageMenu.None,
)

/** A conversation row's rename and pin entries; a null action is one the host does not offer. */
data class ShellConversationManageMenu(
    val pinned: Boolean = false,
    val onRenameRequest: (() -> Unit)? = null,
    val onPinToggle: (() -> Unit)? = null,
) {
    fun items(): List<ShellRowMenuItem> = listOfNotNull(
        onRenameRequest?.let { ShellRowMenuItem("Rename chat", it) },
        onPinToggle?.let { ShellRowMenuItem(if (pinned) "Unpin chat" else "Pin chat", it) },
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
 * The touch row menu, [ShellChromeDecorations.rowMenu]'s default: a long-press on [content] (with a
 * haptic tick) opens the [items] as a popup anchored to the row. A plain tap still reaches the row.
 * No items, no gesture.
 */
@Composable
fun ShellLongPressMenu(items: List<ShellRowMenuItem>, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val onLongPress = remember(haptics) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            open = true
        }
    }
    Box(Modifier.shellLongPress(enabled = items.isNotEmpty(), onLongPress = onLongPress)) {
        content()
        LettaPopupMenu(
            expanded = open && items.isNotEmpty(),
            onDismiss = { open = false },
            items = items.map { LettaMenuItem(label = it.label, onClick = it.onClick) },
            modifier = Modifier.testTag(ShellRowMenuTags.MENU),
        )
    }
}

/**
 * Calls [onLongPress] when a pointer is held still for the long-press timeout. It watches the
 * Initial pass, so it sees the press before a clickable child takes it; once it fires it consumes
 * the rest of the gesture, so the child's click does not also land. A tap, or a drag past the touch
 * slop (a scroll), leaves everything to the child.
 */
fun Modifier.shellLongPress(enabled: Boolean, onLongPress: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        this.then(
            Modifier.pointerInput(onLongPress) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val released = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        awaitReleaseOrSlop(down, viewConfiguration.touchSlop)
                    }
                    if (released == null) {
                        onLongPress()
                        consumeUntilAllUp()
                    }
                }
            },
        )
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
