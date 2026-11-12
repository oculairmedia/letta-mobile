package com.letta.mobile.data.secrets.client

import com.letta.mobile.data.secrets.request.SecretRequestLimits

private const val ZERO_CHAR = '\u0000'
private const val INITIAL_CAPACITY = 32
private const val REPLACEMENT_CODE_POINT = 0xFFFD
private const val ONE_BYTE_LIMIT = 0x80
private const val TWO_BYTE_LIMIT = 0x800
private const val THREE_BYTE_LIMIT = 0x10000
private const val CONTINUATION = 0x80
private const val CONTINUATION_MASK = 0x3F
private const val TWO_BYTE_LEAD = 0xC0
private const val THREE_BYTE_LEAD = 0xE0
private const val FOUR_BYTE_LEAD = 0xF0
private const val SHIFT_6 = 6
private const val SHIFT_12 = 12
private const val SHIFT_18 = 18

/**
 * The characters a person types into the secure form, held in a char array this buffer owns and
 * nowhere else: never a [String], never saved state. The form's text field holds only mask
 * characters and forwards each edit here.
 *
 * Every array this buffer lets go of is zeroed first (when it grows, on [wipe] and when
 * [takeSecretInput] hands the value over), and [toString] never prints the content. The buffer
 * refuses to grow past [MAX_CHARS], which no valid secret exceeds.
 *
 * Not thread safe: the form edits it on the UI thread.
 */
class SecretBuffer {
    private var chars = CharArray(INITIAL_CAPACITY)
    private var size = 0

    /** How many characters the buffer holds. */
    val length: Int get() = size

    /** Whether the buffer holds nothing. */
    val isEmpty: Boolean get() = size == 0

    /**
     * Replaces the characters in [range] with [text], read one character at a time so no string
     * is made. Answers false (and changes nothing) when [range] is outside the buffer or the
     * result would exceed [MAX_CHARS].
     */
    fun replace(range: IntRange, text: CharSequence): Boolean {
        val start = range.first
        val end = range.last + 1
        val accepted = start in 0..end && end <= size
        val newSize = size - (end - start) + text.length
        if (!accepted || newSize > MAX_CHARS) return false
        ensureCapacity(newSize)
        chars.copyInto(chars, destinationOffset = start + text.length, startIndex = end, endIndex = size)
        for (index in text.indices) chars[start + index] = text[index]
        if (newSize < size) chars.fill(ZERO_CHAR, newSize, size)
        size = newSize
        return true
    }

    /**
     * Lends [block] a read-only view of the characters, valid only while [block] runs: the
     * explicit reveal toggle draws through it. Whatever [block] copies out is its caller's to
     * forget; nothing else reads the buffer.
     */
    fun <R> revealChars(block: (CharSequence) -> R): R = block(View())

    /**
     * Hands the characters over as UTF-8 bytes in a [SecretInput] and zeroes this buffer, which is
     * empty afterwards. The caller wipes the returned value once it has been sent.
     */
    fun takeSecretInput(): SecretInput {
        val bytes = ByteArray(utf8Length())
        var position = 0
        var index = 0
        while (index < size) {
            val codePoint = codePointAt(index)
            position = writeUtf8(codePoint, bytes, position)
            index += if (codePoint >= THREE_BYTE_LIMIT) 2 else 1
        }
        wipe()
        return SecretInput.adopt(bytes)
    }

    /** Zeroes every character and empties the buffer. Calling it twice is harmless. */
    fun wipe() {
        chars.fill(ZERO_CHAR)
        size = 0
    }

    /** Never the content: a buffer prints only that it is redacted. */
    override fun toString(): String = REDACTED

    private fun ensureCapacity(needed: Int) {
        if (needed <= chars.size) return
        val grown = chars.copyOf(maxOf(needed, chars.size * 2).coerceAtMost(MAX_CHARS))
        chars.fill(ZERO_CHAR)
        chars = grown
    }

    private fun utf8Length(): Int {
        var total = 0
        var index = 0
        while (index < size) {
            val codePoint = codePointAt(index)
            total += utf8Width(codePoint)
            index += if (codePoint >= THREE_BYTE_LIMIT) 2 else 1
        }
        return total
    }

    /** The code point at [index]: a surrogate pair joined, a lone surrogate as U+FFFD. */
    private fun codePointAt(index: Int): Int {
        val char = chars[index]
        if (!char.isSurrogate()) return char.code
        val next = if (index + 1 < size) chars[index + 1] else ZERO_CHAR
        return if (char.isHighSurrogate() && next.isLowSurrogate()) surrogatePair(char, next) else REPLACEMENT_CODE_POINT
    }

    /** A read-only window onto the buffer that prints nothing. */
    private inner class View : CharSequence {
        override val length: Int get() = size

        override fun get(index: Int): Char = chars[index]

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            throw UnsupportedOperationException(REDACTED)

        override fun toString(): String = REDACTED
    }

    /** Limits and the fixed redaction text. */
    companion object {
        /** The most characters the buffer holds: a UTF-8 character takes at least a byte. */
        const val MAX_CHARS: Int = SecretRequestLimits.MAX_VALUE_BYTES

        /** What [SecretBuffer.toString] answers. */
        const val REDACTED: String = "SecretBuffer(redacted)"
    }
}

private fun surrogatePair(high: Char, low: Char): Int =
    ((high.code - Char.MIN_HIGH_SURROGATE.code) shl 10) + (low.code - Char.MIN_LOW_SURROGATE.code) + THREE_BYTE_LIMIT

private fun utf8Width(codePoint: Int): Int = when {
    codePoint < ONE_BYTE_LIMIT -> 1
    codePoint < TWO_BYTE_LIMIT -> 2
    codePoint < THREE_BYTE_LIMIT -> 3
    else -> 4
}

/** Writes [codePoint] as UTF-8 into [out] at [position]; answers the position after it. */
private fun writeUtf8(codePoint: Int, out: ByteArray, position: Int): Int {
    val width = utf8Width(codePoint)
    out[position] = when (width) {
        1 -> codePoint
        2 -> TWO_BYTE_LEAD or (codePoint shr SHIFT_6)
        3 -> THREE_BYTE_LEAD or (codePoint shr SHIFT_12)
        else -> FOUR_BYTE_LEAD or (codePoint shr SHIFT_18)
    }.toByte()
    for (index in 1 until width) {
        val shift = SHIFT_6 * (width - 1 - index)
        out[position + index] = (CONTINUATION or ((codePoint shr shift) and CONTINUATION_MASK)).toByte()
    }
    return position + width
}
