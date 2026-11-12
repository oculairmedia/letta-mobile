package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Undo2
import com.letta.mobile.data.canvas.mount.client.CanvasMountProblem
import com.letta.mobile.data.canvas.mount.client.CanvasPick
import com.letta.mobile.data.canvas.mount.client.CanvasPickerState
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasPicker
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.shell_canvas_jump
import com.letta.mobile.sharedui.resources.shell_canvas_jump_description
import com.letta.mobile.sharedui.resources.shell_canvas_mount_denied
import com.letta.mobile.sharedui.resources.shell_canvas_mount_invalid
import com.letta.mobile.sharedui.resources.shell_canvas_mount_unavailable
import com.letta.mobile.sharedui.resources.shell_canvas_mount_unknown
import com.letta.mobile.sharedui.resources.shell_canvas_new
import com.letta.mobile.sharedui.resources.shell_canvas_use_own
import com.letta.mobile.ui.canvas.mount.LocalConversationCanvasPicker
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

/** The side menu's view of canvas picks now; nothing mounts while the host offers no picker. */
@Composable
internal fun rememberCanvasPickerState(): CanvasPickerState {
    val picker = LocalConversationCanvasPicker.current ?: return CanvasPickerState()
    val state by remember(picker) { picker.state }.collectAsState(CanvasPickerState())
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
    private val picker: ConversationCanvasPicker?,
    private val actions: ShellAgentPanelActions,
    private val scope: CoroutineScope,
    private val overlays: OverlayHost?,
) {
    /** A canvas row was picked. */
    fun open(canvas: CanvasId) {
        val window = picker?.takeIf { it.mountTarget() != null } ?: return actions.onOpenCanvas(canvas)
        scope.launch { report(window.pick(canvas), canvas) }
    }

    /** "Use this conversation's own canvas" on the mounted row. */
    fun useOwn() {
        val window = picker ?: return
        scope.launch { report(window.useOwnCanvas(), canvas = null) }
    }

    private suspend fun report(pick: CanvasPick, canvas: CanvasId?) {
        when (pick) {
            // Canvas mode ended while the pick was on its way: open it as a pick outside canvas mode does.
            CanvasPick.OpenAsBefore -> canvas?.let(actions.onOpenCanvas)
            is CanvasPick.Mounted -> actions.onCanvasMounted()
            is CanvasPick.Failed -> overlays?.show(Overlay.Snackbar(getString(messageOf(pick.problem)), key = NOTICE_KEY))
        }
    }

    private companion object {
        const val NOTICE_KEY = "shell-canvas-mount"

        fun messageOf(problem: CanvasMountProblem): StringResource = when (problem) {
            CanvasMountProblem.Denied -> Res.string.shell_canvas_mount_denied
            is CanvasMountProblem.InvalidInput -> Res.string.shell_canvas_mount_invalid
            is CanvasMountProblem.Unavailable -> Res.string.shell_canvas_mount_unavailable
            CanvasMountProblem.OutcomeUnknown -> Res.string.shell_canvas_mount_unknown
        }
    }
}

/** This window's canvas picks over [actions]. */
@Composable
internal fun rememberCanvasPicks(actions: ShellAgentPanelActions): ShellCanvasPicks {
    val picker = LocalConversationCanvasPicker.current
    val scope = rememberCoroutineScope()
    val overlays = LocalOverlayHost.current
    return remember(picker, actions, scope, overlays) { ShellCanvasPicks(picker, actions, scope, overlays) }
}

/** The Canvases header's "New canvas", when the host can start one. */
@Composable
internal fun NewCanvasButton(onNewCanvas: () -> Unit) {
    val label = stringResource(Res.string.shell_canvas_new)
    IconButton(onClick = onNewCanvas, modifier = Modifier.size(LettaDimens.Control.iconButton).testTag(ShellCanvasTags.NEW)) {
        Icon(Lucide.Plus, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Beside the chats' filter: jumps down the one list to its Canvases section. */
@Composable
internal fun CanvasJumpButton(onJump: () -> Unit) {
    val description = stringResource(Res.string.shell_canvas_jump_description)
    TextButton(onClick = onJump, modifier = Modifier.testTag(ShellCanvasTags.JUMP).semantics { contentDescription = description }) {
        Text(stringResource(Res.string.shell_canvas_jump), style = MaterialTheme.typography.labelMedium)
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
