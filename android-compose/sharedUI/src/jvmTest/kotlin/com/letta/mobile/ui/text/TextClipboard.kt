@file:OptIn(ExperimentalComposeUiApi::class)

package com.letta.mobile.ui.text

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/** An in-memory clipboard for UI tests: [text] is what it holds, and every Copy replaces it. */
internal class TextClipboard(var text: String? = null) : Clipboard {
    /** Every text written to it, in order. */
    val writes = mutableListOf<String?>()

    override suspend fun getClipEntry(): ClipEntry? = text?.let(::plainTextClipEntry)

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val clip = clipEntry?.nativeClipEntry as? Transferable
        text = clip?.getTransferData(DataFlavor.stringFlavor) as? String
        writes += text
    }

    override val nativeClipboard get() = error("not used")
}
