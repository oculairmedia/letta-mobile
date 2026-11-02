package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.text.input.TextFieldValue

/** Rejects control characters and recognizable image headers introduced by a text-field edit. */
internal fun isUnreadableComposerText(previous: TextFieldValue, next: String): Boolean {
    if (next == previous.text) return false
    val inserted = ComposerTextEdit(previous, next).insertedText()
    return inserted.any(::isForbiddenControl) || ComposerImageSignature(inserted).matches()
}

/** The text a field edit inserted or replaced, used to tell a paste from a keystroke. */
internal fun composerInsertedText(previous: TextFieldValue, next: String): String =
    ComposerTextEdit(previous, next).insertedText()

private fun isForbiddenControl(char: Char): Boolean = char == '\u007f' || (char < ' ' && char !in "\t\n\r")

private class ComposerTextEdit(private val previous: TextFieldValue, private val next: String) {
    fun insertedText(): String = selectionReplacement() ?: changedText()

    /** Returns the inserted text if the edit replaces the selection; IMEs may edit a different range. */
    private fun selectionReplacement(): String? {
        val beforeSelection = previous.text.substring(0, previous.selection.min)
        val afterSelection = previous.text.substring(previous.selection.max)
        if (next.length < beforeSelection.length + afterSelection.length) return null
        if (!next.startsWith(beforeSelection)) return null
        if (!next.endsWith(afterSelection)) return null
        return next.substring(beforeSelection.length, next.length - afterSelection.length)
    }

    /** Finds the changed text when an IME edit did not replace the current selection. */
    private fun changedText(): String {
        val prefix = previous.text.commonPrefixWith(next).length
        val oldRemainder = previous.text.substring(prefix)
        val newRemainder = next.substring(prefix)
        val suffix = oldRemainder.commonSuffixWith(newRemainder).length
        return newRemainder.dropLast(suffix)
    }
}

private class ComposerImageSignature(private val inserted: String) {
    fun matches(): Boolean =
        inserted.startsWith("\u0089PNG\r\n\u001a\n") ||
            inserted.startsWith("\ufffdPNG") ||
            inserted.startsWith("\u00ff\u00d8\u00ff") ||
            inserted.startsWith("\ufffd\ufffd\ufffd") ||
            hasGifHeader(inserted) ||
            hasWebpHeader(inserted) ||
            hasBmpHeader(inserted)
}

// RIFF size field (four decoded bytes) followed by WEBP; readable text needs binary evidence in the size field.
internal fun hasWebpHeader(text: String): Boolean =
    text.length >= 12 && text.startsWith("RIFF") && text.substring(8, 12) == "WEBP" &&
        text.substring(4, 8).any(::isBinaryEvidence)

// A logical screen descriptor has seven decoded byte characters after the signature.
internal fun hasGifHeader(text: String): Boolean {
    if (!text.startsWith("GIF87a") && !text.startsWith("GIF89a")) return false
    if (text.length < 13 || !text.substring(6, 13).all(::isGifDescriptorChar)) return false
    if (text.littleEndian(6, 2) == 0L || text.littleEndian(8, 2) == 0L) return false
    return text.substring(6, 13).any(::isBinaryEvidence)
}

private fun isGifDescriptorChar(char: Char): Boolean = char.code <= 255 || char == '\ufffd'

private fun isBinaryEvidence(char: Char): Boolean =
    isForbiddenControl(char) || char in '\u0080'..'\u009f' || char == '\ufffd'

// BITMAPFILEHEADER and a supported DIB header, represented as byte-valued characters.
private val supportedDibSizes = setOf(12L, 40L, 52L, 56L, 108L, 124L)

internal fun hasBmpHeader(text: String): Boolean =
    text.hasBmpFileHeader() && text.hasSupportedDibHeader() && text.hasOneBmpPlane()

private fun String.hasBmpFileHeader(): Boolean =
    startsWith("BM") && hasByteValuedBmpHeader() && hasClearBmpReservedBytes()

private fun String.hasByteValuedBmpHeader(): Boolean = length >= 26 && take(26).all { it.code <= 255 }

private fun String.hasClearBmpReservedBytes(): Boolean = (6..9).all { this[it] == '\u0000' }

private fun String.hasSupportedDibHeader(): Boolean {
    val fileSize = littleEndian(2, 4)
    val pixelOffset = littleEndian(10, 4)
    val dibSize = littleEndian(14, 4)
    return dibSize in supportedDibSizes && pixelOffset >= 14 + dibSize && fileSize >= pixelOffset
}

private fun String.hasOneBmpPlane(): Boolean {
    val planesOffset = if (littleEndian(14, 4) == 12L) 22 else 26
    return length >= planesOffset + 2 &&
        substring(planesOffset, planesOffset + 2).all { it.code <= 255 } &&
        littleEndian(planesOffset, 2) == 1L
}

private fun String.littleEndian(offset: Int, count: Int): Long {
    var value = 0L
    for (index in 0 until count) value += this[offset + index].code.toLong() shl (index * 8)
    return value
}
