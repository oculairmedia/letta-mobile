package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.letta.mobile.sharedui.resources.shell_canvas_new
import com.letta.mobile.sharedui.resources.shell_canvas_use_own
import com.letta.mobile.ui.host.LocalOverlayHost
import com.letta.mobile.ui.host.Overlay
import com.letta.mobile.ui.host.OverlayHost
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

/** [canvases] with the one mounted for the conversation in canvas mode marked current. */
internal fun markMounted(canvases: List<ShellCanvasRowModel>, mounted: CanvasId?): List<ShellCanvasRowModel> =
    canvases.map { it.copy(mounted = it.id == mounted) }

/**
 * The panel's canvas picks for this window: in canvas mode on a host with mounts a pick mounts the
 * canvas for the conversation on screen, else the host opens it as before ([ShellAgentPanelActions.onOpenCanvas]).
 * A refusal or outage is told through the window's notices in plain words; the board stays.
 */
internal class ShellCanvasPicks(
    private val mounts: ShellCanvasMounts?,
    private val actions: ShellAgentPanelActions,
    private val scope: CoroutineScope,
    private val overlays: OverlayHost?,
) {
    /** A canvas row was picked. */
    fun open(canvas: CanvasId) {
        val window = mounts?.takeIf { it.mountsOnPick() } ?: return actions.onOpenCanvas(canvas)
        scope.launch { report(window.pick(canvas), canvas) }
    }

    /** "Use this conversation's own canvas" on the mounted row. */
    fun useOwn() {
        val window = mounts ?: return
        scope.launch { report(window.useOwn(), canvas = null) }
    }

    private suspend fun report(outcome: ShellCanvasPickOutcome, canvas: CanvasId?) {
        when (outcome) {
            // Canvas mode ended while the pick was on its way: open it as a pick outside canvas mode does.
            ShellCanvasPickOutcome.OpenAsBefore -> canvas?.let(actions.onOpenCanvas)
            ShellCanvasPickOutcome.Mounted -> canvas?.let(actions.onCanvasMounted)
            else -> messageOf(outcome)?.let { overlays?.show(Overlay.Snackbar(getString(it), key = NOTICE_KEY)) }
        }
    }

    private companion object {
        const val NOTICE_KEY = "shell-canvas-mount"

        fun messageOf(outcome: ShellCanvasPickOutcome): StringResource? = when (outcome) {
            ShellCanvasPickOutcome.Denied -> Res.string.shell_canvas_mount_denied
            ShellCanvasPickOutcome.Invalid -> Res.string.shell_canvas_mount_invalid
            ShellCanvasPickOutcome.Unavailable -> Res.string.shell_canvas_mount_unavailable
            ShellCanvasPickOutcome.Unknown -> Res.string.shell_canvas_mount_unknown
            else -> null
        }
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
