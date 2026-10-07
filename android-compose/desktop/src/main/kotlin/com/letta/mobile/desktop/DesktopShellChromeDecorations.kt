package com.letta.mobile.desktop

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import com.letta.mobile.ui.shell.ShellChromeDecorations

/**
 * The pointer host's pieces for the shared shell chrome: cursor tooltips, the right-click row menu,
 * and confirmations as their own dialog window.
 */
internal val DesktopShellChromeDecorations = ShellChromeDecorations(
    tooltip = { text, content -> DesktopTooltip(text = text) { content() } },
    rowMenu = { items, content ->
        ContextMenuArea(items = { items.map { ContextMenuItem(it.label, it.onClick) } }) { content() }
    },
    confirm = { request, onConfirm, onDismiss ->
        DesktopConfirmDialog(
            request = ConfirmDialogRequest(title = request.title, message = request.message, confirmLabel = request.confirmLabel),
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    },
)
