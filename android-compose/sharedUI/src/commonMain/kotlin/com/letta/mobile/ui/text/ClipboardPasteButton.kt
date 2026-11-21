package com.letta.mobile.ui.text

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/**
 * A text field's trailing Paste: hands the clipboard's trimmed text to [onPaste]. An empty clipboard
 * leaves the field as it is; a refused read calls [onBlocked], which says how to paste instead.
 */
@Composable
internal fun ClipboardPasteButton(
    onPaste: (String) -> Unit,
    onBlocked: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    TextButton(
        onClick = { scope.launch { pasteTrimmed(clipboard.readPlainText(), onPaste, onBlocked) } },
        enabled = enabled,
        modifier = modifier,
    ) { Text(PASTE_LABEL) }
}

private fun pasteTrimmed(read: ClipboardTextRead, onPaste: (String) -> Unit, onBlocked: () -> Unit) {
    pasteEntry(read, onBlocked) { it.trim() }?.takeIf { it.isNotEmpty() }?.let(onPaste)
}

private const val PASTE_LABEL = "Paste"
