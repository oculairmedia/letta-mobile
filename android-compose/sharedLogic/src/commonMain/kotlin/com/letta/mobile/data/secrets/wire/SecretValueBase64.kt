package com.letta.mobile.data.secrets.wire

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
private const val VALUE_BYTES_LIMIT = 64 * 1024
private const val PAD = '='
private const val QUAD = 4
private const val TRIPLE = 3
private const val SEXTET_BITS = 6
private const val BYTE_BITS = 8
private const val GROUP_TOP_SHIFT = 18
private const val BYTE_TOP_SHIFT = 16
private const val SEXTET_MASK = 0x3F
private const val BYTE_MASK = 0xFF
private const val ONE_PAD_MASK = 0xFF
private const val TWO_PAD_MASK = 0xFFFF

/**
 * The `value` field of `secret.request.submit` (contract M1): standard padded base64 of the secret
 * bytes, encoded and decoded without ever making a [String] of the value. [encode] answers a char
 * array its caller zeroes; [decode] reads a [CharSequence] (the JSON layer's string, which no code
 * can zero) into a fresh byte array its caller zeroes. Only canonical text is accepted: length a
 * multiple of four, the standard alphabet, padding only at the end and zero padding bits.
 */
object SecretValueBase64 {
    /** The most base64 characters a valid value can take: 4 * ceil(65536 / 3). */
    const val MAX_ENCODED_CHARS: Int = (VALUE_BYTES_LIMIT + TRIPLE - 1) / TRIPLE * QUAD

    /** The largest value in bytes, the keystore limit (equal to `SecretRequestLimits.MAX_VALUE_BYTES`, checked by a test). */
    const val MAX_VALUE_BYTES: Int = VALUE_BYTES_LIMIT

    /** [bytes] as base64 characters; the caller zeroes the returned array once it is sent. */
    fun encode(bytes: ByteArray): CharArray {
        val out = CharArray((bytes.size + TRIPLE - 1) / TRIPLE * QUAD)
        var index = 0
        while (index < bytes.size) {
            val taken = minOf(TRIPLE, bytes.size - index)
            writeQuad(groupOf(bytes, index, taken), taken, out, index / TRIPLE * QUAD)
            index += TRIPLE
        }
        return out
    }

    /** How many bytes [text] decodes to, read from its length and padding alone; null when the length is not base64. */
    fun decodedLength(text: CharSequence): Int? =
        if (text.length % QUAD != 0) null else text.length / QUAD * TRIPLE - paddingOf(text)

    /**
     * [text] decoded into a new array the caller zeroes, or null when it is not canonical padded
     * base64. A partly decoded array is zeroed before null is answered.
     */
    fun decode(text: CharSequence): ByteArray? {
        val length = decodedLength(text) ?: return null
        val out = ByteArray(length)
        if (fill(text, out)) return out
        out.fill(0)
        return null
    }

    private fun fill(text: CharSequence, out: ByteArray): Boolean {
        val quads = text.length / QUAD
        for (quad in 0 until quads) {
            val padding = if (quad == quads - 1) paddingOf(text) else 0
            val group = quadAt(text, quad * QUAD, padding)
            if (group < 0 || !paddingBitsClear(group, padding)) return false
            writeBytes(group, out, quad * TRIPLE)
        }
        return true
    }
}

private fun paddingOf(text: CharSequence): Int = when {
    text.endsWith("$PAD$PAD") -> 2
    text.endsWith(PAD) -> 1
    else -> 0
}

private fun groupOf(bytes: ByteArray, start: Int, taken: Int): Int {
    var group = 0
    for (offset in 0 until TRIPLE) {
        val byte = if (offset < taken) bytes[start + offset].toInt() and BYTE_MASK else 0
        group = (group shl BYTE_BITS) or byte
    }
    return group
}

private fun writeQuad(group: Int, taken: Int, out: CharArray, at: Int) {
    for (offset in 0 until QUAD) {
        val sextet = (group shr (GROUP_TOP_SHIFT - SEXTET_BITS * offset)) and SEXTET_MASK
        out[at + offset] = if (offset <= taken) ALPHABET[sextet] else PAD
    }
}

/** The 24-bit group of the quad at [start], padding read as zero bits; -1 when a character is not allowed there. */
private fun quadAt(text: CharSequence, start: Int, padding: Int): Int {
    var group = 0
    for (offset in 0 until QUAD) {
        val sextet = sextetAt(text[start + offset], offset >= QUAD - padding)
        if (sextet < 0) return -1
        group = (group shl SEXTET_BITS) or sextet
    }
    return group
}

private fun sextetAt(char: Char, padded: Boolean): Int = when {
    padded -> if (char == PAD) 0 else -1
    else -> ALPHABET.indexOf(char)
}

private fun paddingBitsClear(group: Int, padding: Int): Boolean = when (padding) {
    1 -> group and ONE_PAD_MASK == 0
    2 -> group and TWO_PAD_MASK == 0
    else -> true
}

private fun writeBytes(group: Int, out: ByteArray, at: Int) {
    for (offset in 0 until TRIPLE) {
        if (at + offset < out.size) out[at + offset] = (group shr (BYTE_TOP_SHIFT - BYTE_BITS * offset)).toByte()
    }
}
