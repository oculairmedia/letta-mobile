@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import kotlin.js.Promise
import kotlinx.coroutines.await

/**
 * The browser's clipboard read. Compose's web clipboard reads through
 * `navigator.clipboard.read()` and turns a refusal into an empty clip, so a Paste in a page without
 * clipboard-read permission does nothing. This reads `navigator.clipboard.readText()` itself, so a
 * refusal stays [ClipboardTextRead.Blocked]. The receiver is not consulted: the browser is the one clipboard.
 */
internal actual suspend fun Clipboard.readPlainText(): ClipboardTextRead = readClipboardText {
    readBrowserClipboardText().await<JsString>().toString()
}

/** The page's clipboard: writes go to Compose's; a text field's Paste reads through [readPlainText]. */
@Composable
internal actual fun ProvidePlatformClipboard(onPasteBlocked: () -> Unit, content: @Composable () -> Unit) {
    val platform = LocalClipboard.current
    val blocked = rememberUpdatedState(onPasteBlocked)
    val clipboard = remember(platform) { WebClipboard(platform) { blocked.value() } }
    CompositionLocalProvider(LocalClipboard provides clipboard, content = content)
}

private class WebClipboard(private val platform: Clipboard, private val onBlocked: () -> Unit) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = pasteEntry(readPlainText(), onBlocked) { ClipEntry.withPlainText(it) }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = platform.setClipEntry(clipEntry)
}

/** `navigator.clipboard.readText()`; rejects when the page has no clipboard read or the read is refused. */
private fun readBrowserClipboardText(): Promise<JsString> = js(
    """(navigator.clipboard && navigator.clipboard.readText)
        ? navigator.clipboard.readText()
        : Promise.reject(new Error('This page cannot read the clipboard'))""",
)
