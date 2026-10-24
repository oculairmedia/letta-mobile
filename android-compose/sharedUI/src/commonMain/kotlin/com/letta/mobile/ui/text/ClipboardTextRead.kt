package com.letta.mobile.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.Clipboard
import kotlin.coroutines.cancellation.CancellationException

/**
 * What reading the clipboard's plain text found. A refused or failed read
 * is [Blocked], never [Empty]: the caller tells the person how to paste instead of doing nothing.
 */
internal sealed interface ClipboardTextRead {
    /** The clipboard held this text. */
    data class Readable(val text: String) : ClipboardTextRead

    /** The read went through and found no text. */
    data object Empty : ClipboardTextRead

    /** The platform refused the read or it failed: a browser page without clipboard-read permission. */
    data object Blocked : ClipboardTextRead
}

/**
 * Reads the clipboard's plain text. A page's keyboard paste (Ctrl+V / Cmd+V) does not come through
 * here: Compose's text fields take it from the browser's paste event, which needs no permission.
 */
internal expect suspend fun Clipboard.readPlainText(): ClipboardTextRead

/**
 * Makes the window's clipboard report to [onPasteBlocked] when a text field's Paste could not read it,
 * so that Paste says so instead of doing nothing. A platform whose reads are not gated provides it as is.
 */
@Composable
internal expect fun ProvidePlatformClipboard(onPasteBlocked: () -> Unit, content: @Composable () -> Unit)

/** The outcome of [read]: [ClipboardTextRead.Readable] for any text, [ClipboardTextRead.Empty] for none, and [ClipboardTextRead.Blocked] when it throws. */
internal suspend fun readClipboardText(read: suspend () -> String?): ClipboardTextRead {
    val text = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        return ClipboardTextRead.Blocked
    }
    return if (text.isNullOrEmpty()) ClipboardTextRead.Empty else ClipboardTextRead.Readable(text)
}

/**
 * What a text field's Paste gets for [read]: the text as an entry, nothing when the clipboard is empty,
 * and nothing plus [onBlocked] when the read was refused.
 */
internal inline fun <T : Any> pasteEntry(read: ClipboardTextRead, onBlocked: () -> Unit, entry: (String) -> T): T? = when (read) {
    is ClipboardTextRead.Readable -> entry(read.text)
    ClipboardTextRead.Empty -> null
    ClipboardTextRead.Blocked -> {
        onBlocked()
        null
    }
}
