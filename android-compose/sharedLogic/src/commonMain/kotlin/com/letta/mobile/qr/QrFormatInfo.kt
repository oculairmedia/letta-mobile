package com.letta.mobile.qr

/** BCH-protected format and version information words (ISO/IEC 18004 sections 7.9 and 7.10, Annex C and D). */
internal object QrFormatInfo {
    private const val FORMAT_GENERATOR = 0x537
    private const val FORMAT_MASK = 0x5412
    private const val VERSION_GENERATOR = 0x1F25

    /** The 15-bit format word for [level] and [mask]: 5 data bits, 10 BCH bits, XOR 101010000010010. */
    fun formatBits(level: QrErrorCorrection, mask: Int): Int {
        val data = (level.formatBits shl 3) or mask
        var remainder = data
        repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * FORMAT_GENERATOR) }
        return ((data shl 10) or remainder) xor FORMAT_MASK
    }

    /** The 18-bit version word for [version] (7-40): 6 data bits followed by 12 BCH bits. */
    fun versionBits(version: Int): Int {
        var remainder = version
        repeat(12) { remainder = (remainder shl 1) xor ((remainder ushr 11) * VERSION_GENERATOR) }
        return (version shl 12) or remainder
    }
}
