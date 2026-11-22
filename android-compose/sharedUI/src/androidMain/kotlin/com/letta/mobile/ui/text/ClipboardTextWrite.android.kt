package com.letta.mobile.ui.text

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

/** The Android clip: one plain-text item. */
internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(ClipData.newPlainText(PLAIN_TEXT_LABEL, text))

/** The clip's label (Android shows it to accessibility services, not on screen). */
private const val PLAIN_TEXT_LABEL = "text"
