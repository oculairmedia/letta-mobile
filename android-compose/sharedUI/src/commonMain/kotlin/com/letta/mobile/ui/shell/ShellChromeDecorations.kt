package com.letta.mobile.ui.shell

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** One entry of a row's secondary menu (desktop: right-click). */
@Immutable
data class ShellRowMenuItem(val label: String, val onClick: () -> Unit)

/** A destructive action waiting for the user's yes. */
@Immutable
data class ShellConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
)

/**
 * The platform pieces the shared shell chrome (agent panel, rail) wraps around its rows. The defaults
 * suit a touch host: no hover tooltips, no secondary menu, a Material dialog for confirmations. A
 * pointer host provides cursor tooltips, a right-click menu and its own dialog window through
 * [LocalShellChromeDecorations].
 */
@Immutable
class ShellChromeDecorations(
    /** Wraps a control with a hover label. */
    val tooltip: @Composable (text: String, content: @Composable () -> Unit) -> Unit = { _, content -> content() },
    /** Wraps a row with its secondary actions (archive, delete). */
    val rowMenu: @Composable (items: List<ShellRowMenuItem>, content: @Composable () -> Unit) -> Unit =
        { _, content -> content() },
    /** Asks before a destructive action. */
    val confirm: @Composable (request: ShellConfirmRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) -> Unit =
        { request, onConfirm, onDismiss -> ShellConfirmAlert(request, onConfirm, onDismiss) },
)

/** The decorations in effect; a host overrides them once around the shell chrome. */
val LocalShellChromeDecorations = staticCompositionLocalOf { ShellChromeDecorations() }

@Composable
private fun ShellConfirmAlert(request: ShellConfirmRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = { Text(request.message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(request.confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
