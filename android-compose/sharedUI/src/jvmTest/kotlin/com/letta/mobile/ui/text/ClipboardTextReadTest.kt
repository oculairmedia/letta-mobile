@file:OptIn(ExperimentalComposeUiApi::class)

package com.letta.mobile.ui.text

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/** A clipboard read is honest about what it found. */
class ClipboardTextReadTest {
    @Test
    fun aClipWithTextIsReadable() = runTest {
        assertEquals(ClipboardTextRead.Readable("iroh://node"), clipboard { ClipEntry(StringSelection("iroh://node")) }.readPlainText())
    }

    @Test
    fun noClipOrNoTextIsEmpty() = runTest {
        assertEquals(ClipboardTextRead.Empty, clipboard { null }.readPlainText())
        assertEquals(ClipboardTextRead.Empty, clipboard { ClipEntry(StringSelection("")) }.readPlainText())
        assertEquals(ClipboardTextRead.Empty, clipboard { ClipEntry(NoText) }.readPlainText())
    }

    @Test
    fun aRefusedReadIsBlockedNotEmpty() = runTest {
        assertEquals(ClipboardTextRead.Blocked, clipboard { throw IllegalStateException("clipboard refused") }.readPlainText())
    }

    @Test
    fun cancellingTheReadIsNotABlockedRead() = runTest {
        assertFailsWith<CancellationException> { clipboard { throw CancellationException("left") }.readPlainText() }
    }

    @Test
    fun aMenuPasteGetsTheTextAndReportsOnlyARefusal() {
        var reported = 0
        val entry = { text: String -> "entry:$text" }

        assertEquals("entry:x", pasteEntry(ClipboardTextRead.Readable("x"), { reported++ }, entry))
        assertNull(pasteEntry(ClipboardTextRead.Empty, { reported++ }, entry))
        assertEquals(0, reported)
        assertNull(pasteEntry(ClipboardTextRead.Blocked, { reported++ }, entry))
        assertEquals(1, reported)
    }

    private fun clipboard(read: () -> ClipEntry?): Clipboard = object : Clipboard {
        override suspend fun getClipEntry(): ClipEntry? = read()

        override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit
    }

    /** A clip that holds something other than text (an image, a file). */
    private object NoText : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = emptyArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = false

        override fun getTransferData(flavor: DataFlavor): Any = error("no data")
    }
}
