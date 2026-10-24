package com.letta.mobile.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/** The clip's string flavor; a clipboard the system will not open is [ClipboardTextRead.Blocked]. */
@OptIn(ExperimentalComposeUiApi::class)
internal actual suspend fun Clipboard.readPlainText(): ClipboardTextRead = readClipboardText {
    val clip = getClipEntry()?.nativeClipEntry as? Transferable
    clip?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }?.getTransferData(DataFlavor.stringFlavor) as? String
}

/** The desktop clipboard's reads are not permission-gated: the clipboard is provided as is. */
@Composable
internal actual fun ProvidePlatformClipboard(onPasteBlocked: () -> Unit, content: @Composable () -> Unit) = content()
