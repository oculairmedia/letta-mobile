package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.ui.text.input.TextFieldValue

/** Rejects control characters and recognizable image headers introduced by a text-field edit. */
internal fun isUnreadableComposerText(previous: TextFieldValue, next: String): Boolean {
    if (next == previous.text) return false
    val inserted = composerInsertedText(previous, next)
    return inserted.any(::isForbiddenControl) || hasImageSignature(inserted)
}

internal fun composerInsertedText(previous: TextFieldValue, next: String): String =
    selectionReplacement(previous, next) ?: changedText(previous.text, next)

private fun isForbiddenControl(char: Char): Boolean = char == '\u007f' || (char < ' ' && char !in "\t\n\r")

/** Returns the inserted text if the edit replaces the selection; IMEs may edit a different range. */
private fun selectionReplacement(previous: TextFieldValue, next: String): String? {
    val beforeSelection = previous.text.substring(0, previous.selection.min)
    val afterSelection = previous.text.substring(previous.selection.max)
    if (next.length < beforeSelection.length + afterSelection.length) return null
    if (!next.startsWith(beforeSelection)) return null
    if (!next.endsWith(afterSelection)) return null
    return next.substring(beforeSelection.length, next.length - afterSelection.length)
}

/** Finds the changed text when an IME edit did not replace the current selection. */
private fun changedText(previous: String, next: String): String {
    val prefix = previous.commonPrefixWith(next).length
    val oldRemainder = previous.substring(prefix)
    val newRemainder = next.substring(prefix)
    val suffix = oldRemainder.commonSuffixWith(newRemainder).length
    return newRemainder.dropLast(suffix)
}

private fun hasImageSignature(inserted: String): Boolean =
    inserted.startsWith("\u0089PNG\r\n\u001a\n") ||
        inserted.startsWith("\ufffdPNG") ||
        inserted.startsWith("\u00ff\u00d8\u00ff") ||
        inserted.startsWith("\ufffd\ufffd\ufffd") ||
        inserted.startsWith("GIF87a") || inserted.startsWith("GIF89a") ||
        (inserted.startsWith("RIFF") && inserted.length >= 12 && inserted.substring(8, 12) == "WEBP") ||
        hasBmpHeader(inserted)

// BITMAPFILEHEADER and a supported DIB header, represented as byte-valued characters.
internal fun hasBmpHeader(text: String): Boolean {
    if (!text.startsWith("BM") || text.length < 26 || text.take(26).any { it.code > 255 } ||
        (6..9).any { text[it] != '\u0000' }
    ) return false
    val fileSize = littleEndian(text, 2, 4)
    val pixelOffset = littleEndian(text, 10, 4)
    val dibSize = littleEndian(text, 14, 4)
    if (dibSize !in listOf(12L, 40L, 52L, 56L, 108L, 124L) ||
        pixelOffset < 14 + dibSize || fileSize < pixelOffset
    ) return false
    val planesOffset = if (dibSize == 12L) 22 else 26
    if (text.length < planesOffset + 2 || text.substring(planesOffset, planesOffset + 2).any { it.code > 255 }) return false
    return littleEndian(text, planesOffset, 2) == 1L
}

private fun littleEndian(text: String, offset: Int, count: Int): Long {
    var value = 0L
    for (index in 0 until count) value += text[offset + index].code.toLong() shl (index * 8)
    return value
}
