package com.letta.mobile.qr

/** An append-only big-endian bit sequence. */
internal class QrBitBuffer {
    private val bits = ArrayList<Boolean>()

    val size: Int get() = bits.size

    /** Append the low [length] bits of [value], most significant first. */
    fun append(value: Int, length: Int) {
        for (i in length - 1 downTo 0) bits += ((value ushr i) and 1) != 0
    }

    fun appendAll(other: QrBitBuffer) {
        bits.addAll(other.bits)
    }

    /** Pack into bytes; the size must be a multiple of 8. */
    fun toCodewords(): IntArray = IntArray(bits.size / 8) { index ->
        (0 until 8).fold(0) { acc, bit -> (acc shl 1) or (if (bits[index * 8 + bit]) 1 else 0) }
    }
}

/** QR data modes this encoder emits, with the character-count field widths for versions 1-9, 10-26 and 27-40. */
internal enum class QrMode(val indicator: Int, private val countBits: IntArray) {
    ALPHANUMERIC(0x2, intArrayOf(9, 11, 13)),
    BYTE(0x4, intArrayOf(8, 16, 16)),
    ;

    fun countBits(version: Int): Int = countBits[if (version <= 9) 0 else if (version <= 26) 1 else 2]
}

/**
 * One text payload as a single QR segment: alphanumeric when every character is in the
 * 45-character QR set, otherwise UTF-8 bytes, preceded by an ECI (UTF-8) header when any
 * character is outside ASCII so readers do not fall back to ISO-8859-1.
 */
internal class QrData private constructor(
    val mode: QrMode,
    private val count: Int,
    private val payload: QrBitBuffer,
    private val utf8Eci: Boolean,
) {
    /** Bits this payload needs in a symbol of [version], before terminator and padding. */
    fun bitLength(version: Int): Int = (if (utf8Eci) ECI_HEADER_BITS else 0) + 4 + mode.countBits(version) + payload.size

    /** Whether the payload fits the data capacity of [version] at [level]. */
    fun fits(version: Int, level: QrErrorCorrection): Boolean =
        count < (1 shl mode.countBits(version)) && bitLength(version) <= capacityBits(version, level)

    /** The data codewords for [version] at [level]: header, payload, terminator and pad bytes. */
    fun codewords(version: Int, level: QrErrorCorrection): IntArray {
        val capacity = capacityBits(version, level)
        val buffer = QrBitBuffer()
        if (utf8Eci) {
            buffer.append(ECI_MODE, 4)
            buffer.append(UTF8_ECI_DESIGNATOR, 8)
        }
        buffer.append(mode.indicator, 4)
        buffer.append(count, mode.countBits(version))
        buffer.appendAll(payload)
        buffer.append(0, minOf(4, capacity - buffer.size))
        buffer.append(0, (8 - buffer.size % 8) % 8)
        var pad = 0
        while (buffer.size < capacity) buffer.append(PAD_BYTES[pad++ % 2], 8)
        return buffer.toCodewords()
    }

    companion object {
        private const val ECI_MODE = 0x7
        private const val UTF8_ECI_DESIGNATOR = 26
        private const val ECI_HEADER_BITS = 12
        private val PAD_BYTES = intArrayOf(0xEC, 0x11)
        private const val ALPHANUMERIC_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

        /** Data capacity in bits of [version] at [level]. */
        fun capacityBits(version: Int, level: QrErrorCorrection): Int = QrVersionTables.dataCodewords(version, level) * 8

        /** The segment for [text]. */
        fun of(text: String): QrData =
            if (text.all { it in ALPHANUMERIC_CHARSET }) alphanumeric(text) else bytes(text)

        private fun alphanumeric(text: String): QrData {
            val buffer = QrBitBuffer()
            var i = 0
            while (i + 1 < text.length) {
                buffer.append(ALPHANUMERIC_CHARSET.indexOf(text[i]) * 45 + ALPHANUMERIC_CHARSET.indexOf(text[i + 1]), 11)
                i += 2
            }
            if (i < text.length) buffer.append(ALPHANUMERIC_CHARSET.indexOf(text[i]), 6)
            return QrData(QrMode.ALPHANUMERIC, text.length, buffer, utf8Eci = false)
        }

        private fun bytes(text: String): QrData {
            val bytes = text.encodeToByteArray()
            val buffer = QrBitBuffer()
            bytes.forEach { buffer.append(it.toInt() and 0xFF, 8) }
            return QrData(QrMode.BYTE, bytes.size, buffer, utf8Eci = bytes.any { it < 0 })
        }
    }
}
