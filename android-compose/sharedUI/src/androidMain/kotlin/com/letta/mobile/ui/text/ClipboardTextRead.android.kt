package com.letta.mobile.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.Clipboard

/** The first clip item's text; a refused read (a SecurityException) is [ClipboardTextRead.Blocked]. */
internal actual suspend fun Clipboard.readPlainText(): ClipboardTextRead = readClipboardText {
    getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
}

/** Android's clipboard reads are not permission-gated in the foreground: the clipboard is provided as is. */
@Composable
internal actual fun ProvidePlatformClipboard(onPasteBlocked: () -> Unit, content: @Composable () -> Unit) = content()
