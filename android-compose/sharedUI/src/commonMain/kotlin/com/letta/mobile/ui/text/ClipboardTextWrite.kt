package com.letta.mobile.ui.text

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

/** [text] as the platform's plain-text clip: what a Copy puts on the clipboard. */
internal expect fun plainTextClipEntry(text: String): ClipEntry

/** Puts [text] on the clipboard as plain text, through the window's clipboard (LocalClipboard). */
internal suspend fun Clipboard.writePlainText(text: String) = setClipEntry(plainTextClipEntry(text))
