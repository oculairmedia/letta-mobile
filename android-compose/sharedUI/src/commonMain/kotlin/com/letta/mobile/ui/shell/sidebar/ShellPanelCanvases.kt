package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.composables.icons.lucide.ArrowDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Undo2
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.shell_canvas_jump_description
import com.letta.mobile.sharedui.resources.shell_canvas_mount_denied
import com.letta.mobile.sharedui.resources.shell_canvas_mount_invalid
import com.letta.mobile.sharedui.resources.shell_canvas_mount_unavailable
import com.letta.mobile.sharedui.resources.shell_canvas_mount_unknown
import com.letta.mobile.sharedui.resources.shell_canvas_mount_unsaved
import com.letta.mobile.sharedui.resources.shell_canvas_new
import com.letta.mobile.sharedui.resources.shell_canvas_open_in_pane
import com.letta.mobile.sharedui.resources.shell_canvas_use_own
import com.letta.mobile.ui.host.LocalOverlayHost
import com.letta.mobile.ui.host.Overlay
import com.letta.mobile.ui.host.OverlayHost
import com.letta.mobile.ui.host.PaneWindowClass
import com.letta.mobile.ui.host.currentPaneWindowClass
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Test hooks for the panel's canvas controls. */
object ShellCanvasTags {
    const val NEW = "shell-canvas-new"
    const val JUMP = "shell-canvas-jump"
    const val USE_OWN = "shell-canvas-use-own"
}

/** The canvases section's key in the panel's roster. */
internal const val CANVASES_SECTION = "canvases"

/** The side menu's view of canvas mounts now; nothing mounts while the host offers none. */
@Composable
internal fun rememberCanvasMountState(): ShellCanvasMountState {
    val mounts = LocalShellCanvasMounts.current ?: return ShellCanvasMountState()
    val state by remember(mounts) { mounts.state }.collectAsState(ShellCanvasMountState())
    return state
}

/** [canvases] with the one mounted for the conversation on screen marked current (and whether only this device holds it). */
internal fun markMounted(canvases: List<ShellCanvasRowModel>, mounts: ShellCanvasMountState): List<ShellCanvasRowModel> =
    canvases.map { row ->
        val mounted = row.id == mounts.mounted
        row.copy(mounted = mounted, mountedHereOnly = mounted && mounts.deviceOnly)
    }

/**
 * The panel's canvas picks for this window, by [ShellCanvasMounts.pickPlan]: in canvas mode a pick
 * mounts the canvas for the conversation on screen and opens nothing; in chat mode it is mounted and
 * also opened as before ([ShellAgentPanelActions.onOpenCanvas], a side pane where the host has one);
 * with no conversation on screen it only opens. "Open in side panel" opens a pane and leaves the
 * mount alone. A refusal or outage is told through the window's notices in plain words; the board stays.
 */
internal class ShellCanvasPicks(
    private val mounts: ShellCanvasMounts?,
    private val actions: ShellAgentPanelActions,
    private val scope: CoroutineScope,
    private val overlays: OverlayHost?,
) {
    /** A canvas row was picked. */
    fun open(canvas: CanvasId) {
        val window = mounts?.takeIf { it.pickPlan() != ShellCanvasPickPlan.Open } ?: return actions.onOpenCanvas(canvas)
        scope.launch { pick(window, canvas) }
    }

    /**
     * A chat card's "Show on canvas" for [canvas], with the menu's view [now]: a canvas already on the
     * board goes straight to [onBoard]; another is picked exactly as from the list (the board on show
     * saved first), and [onBoard] follows only once it is the conversation's board. A refusal is told
     * and [onBoard] never runs. With no conversation to mount on, [onBoard] runs as before. When the
     * person moved to another conversation while the pick was on its way, the mount stands but
     * [onBoard] is skipped, so the new conversation is not framed or opened on its board.
     */
    fun bringToBoard(canvas: CanvasId, now: ShellCanvasMountState, onBoard: () -> Unit) {
        val window = mounts?.takeIf { now.mountsOnPick && canvas != (now.mounted ?: now.own) } ?: return onBoard()
        val conversation = window.ownOnScreen()
        scope.launch { if (pick(window, canvas) && window.ownOnScreen() == conversation) onBoard() }
    }

    /** The pick of [canvas] by the plan now; true once it is the conversation's board. */
    private suspend fun pick(window: ShellCanvasMounts, canvas: CanvasId): Boolean = when (window.pickPlan()) {
        ShellCanvasPickPlan.Open -> false.also { actions.onOpenCanvas(canvas) }
        ShellCanvasPickPlan.Mount -> report(window.pick(canvas), canvas, onOpenAsBefore = canvas)
        ShellCanvasPickPlan.OpenAndMount -> openAndMount(window, canvas)
    }

    /** The row menu's "Open in side panel": a pane, whatever the mode, without touching the mount. */
    fun openInPane(canvas: CanvasId) {
        actions.onOpenCanvasInPane?.invoke(canvas)
    }

    /** "Use this conversation's own canvas" ([own]): once back on it the host is told, as after a pick. */
    fun useOwn(own: CanvasId) {
        val window = mounts ?: return
        scope.launch { report(window.useOwn(), own, onOpenAsBefore = null) }
    }

    /**
     * Chat mode: the mount lands first, then the canvas opens, because opening may close the phone's
     * drawer and with it this scope. A failed mount is told; the canvas still opens.
     */
    private suspend fun openAndMount(window: ShellCanvasMounts, canvas: CanvasId): Boolean {
        val outcome = window.pick(canvas)
        notify(outcome)
        actions.onOpenCanvas(canvas)
        return outcome == ShellCanvasPickOutcome.Mounted
    }

    /**
     * [onBoard] is the board on show after a switch; [onOpenAsBefore] what opens once no conversation
     * is on screen. True when the board switched.
     */
    private suspend fun report(outcome: ShellCanvasPickOutcome, onBoard: CanvasId, onOpenAsBefore: CanvasId?): Boolean {
        when (outcome) {
            // The conversation left the screen while the pick was on its way: open it as a pick without one does.
            ShellCanvasPickOutcome.OpenAsBefore -> onOpenAsBefore?.let(actions.onOpenCanvas)
            ShellCanvasPickOutcome.Mounted -> actions.onCanvasMounted(onBoard)
            else -> notify(outcome)
        }
        return outcome == ShellCanvasPickOutcome.Mounted
    }

    private suspend fun notify(outcome: ShellCanvasPickOutcome) {
        messageOf(outcome)?.let { overlays?.show(Overlay.Snackbar(getString(it), key = NOTICE_KEY)) }
    }

    private companion object {
        const val NOTICE_KEY = "shell-canvas-mount"

        fun messageOf(outcome: ShellCanvasPickOutcome): StringResource? = when (outcome) {
            ShellCanvasPickOutcome.Denied -> Res.string.shell_canvas_mount_denied
            ShellCanvasPickOutcome.Invalid -> Res.string.shell_canvas_mount_invalid
            ShellCanvasPickOutcome.Unavailable -> Res.string.shell_canvas_mount_unavailable
            ShellCanvasPickOutcome.Unknown -> Res.string.shell_canvas_mount_unknown
            ShellCanvasPickOutcome.Unsaved -> Res.string.shell_canvas_mount_unsaved
            else -> null
        }
    }
}

/**
 * A canvas row menu's extras: the way back's label while a conversation is on screen (to its [own]
 * canvas), and "Open in side panel"'s where the window is wide enough for a side pane and the host
 * has one (null hides each), with the canvases the latter leaves out.
 */
internal class ShellCanvasMenu(val useOwnLabel: String?, val paneLabel: String?, val notForPane: Set<CanvasId>, val own: CanvasId?)

@Composable
internal fun rememberCanvasMenu(mounts: ShellCanvasMountState, actions: ShellAgentPanelActions): ShellCanvasMenu {
    val useOwn = stringResource(Res.string.shell_canvas_use_own).takeIf { mounts.mountsOnPick }
    val wide = currentPaneWindowClass() == PaneWindowClass.Wide
    val pane = stringResource(Res.string.shell_canvas_open_in_pane).takeIf { wide }?.takeIf { actions.onOpenCanvasInPane != null }
    return remember(useOwn, pane, mounts.notForPane, mounts.own) { ShellCanvasMenu(useOwn, pane, mounts.notForPane, mounts.own) }
}

/**
 * The way back to the conversation's own canvas when the mounted canvas has no row in [canvases]
 * (filtered out, or not in the library), so it is never lost; null while the mounted row offers it.
 */
internal fun strayWayBack(canvases: List<ShellCanvasRowModel>, mounts: ShellCanvasMountState, picks: ShellCanvasPicks): (() -> Unit)? {
    val own = mounts.own?.takeIf { mounts.mounted != null } ?: return null
    return if (canvases.any { it.mounted }) null else ({ picks.useOwn(own) })
}

/** The Canvases header's end: the [wayBack] when the mounted canvas has no row, then New canvas; null with neither. */
internal fun canvasHeaderEnd(onNewCanvas: (() -> Unit)?, wayBack: (() -> Unit)?): (@Composable () -> Unit)? {
    if (onNewCanvas == null && wayBack == null) return null
    return { CanvasHeaderButtons(onNewCanvas, wayBack) }
}

@Composable
private fun CanvasHeaderButtons(onNewCanvas: (() -> Unit)?, wayBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        wayBack?.let { UseOwnCanvasButton(it) }
        onNewCanvas?.let { NewCanvasButton(it) }
    }
}

/** This window's canvas picks over [actions]. */
@Composable
internal fun rememberCanvasPicks(actions: ShellAgentPanelActions): ShellCanvasPicks {
    val mounts = LocalShellCanvasMounts.current
    val scope = rememberCoroutineScope()
    val overlays = LocalOverlayHost.current
    return remember(mounts, actions, scope, overlays) { ShellCanvasPicks(mounts, actions, scope, overlays) }
}

/** The Canvases header's "New canvas", when the host can start one. */
@Composable
internal fun NewCanvasButton(onNewCanvas: () -> Unit) {
    val label = stringResource(Res.string.shell_canvas_new)
    IconButton(onClick = onNewCanvas, modifier = Modifier.size(LettaDimens.Control.iconButton).testTag(ShellCanvasTags.NEW)) {
        Icon(Lucide.Plus, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Beside the chats' filter: jumps down the one list to its Canvases section. An icon, not a
 * label: in a narrow sidebar a label wrapped letter by letter and stretched the filter row.
 */
@Composable
internal fun CanvasJumpButton(onJump: () -> Unit) {
    val description = stringResource(Res.string.shell_canvas_jump_description)
    IconButton(onClick = onJump, modifier = Modifier.size(LettaDimens.Control.iconButton).testTag(ShellCanvasTags.JUMP)) {
        Icon(Lucide.ArrowDown, contentDescription = description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The mounted row's "Use this conversation's own canvas". */
@Composable
internal fun UseOwnCanvasButton(onUseOwn: () -> Unit) {
    val label = stringResource(Res.string.shell_canvas_use_own)
    IconButton(onClick = onUseOwn, modifier = Modifier.size(LettaDimens.Control.iconButton).testTag(ShellCanvasTags.USE_OWN)) {
        Icon(Lucide.Undo2, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
