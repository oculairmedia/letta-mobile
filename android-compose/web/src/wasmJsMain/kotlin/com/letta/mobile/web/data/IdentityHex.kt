package com.letta.mobile.web.data

/**
 * The browser identity crosses the JS/Wasm boundary as lowercase hex (`iroh-identity.js` toHex/fromHex).
 * These two small loops replace the stdlib HexFormat path, which pulls its general-purpose
 * parser into the web bundle for one fixed-format key.
 */
internal object IdentityHex {
    private const val DIGITS = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val LOW_NIBBLE = 0x0f
    private const val BYTE_MASK = 0xff
    private const val DIGITS_PER_BYTE = 2

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * DIGITS_PER_BYTE)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and BYTE_MASK
            out[index * DIGITS_PER_BYTE] = DIGITS[value ushr NIBBLE_BITS]
            out[index * DIGITS_PER_BYTE + 1] = DIGITS[value and LOW_NIBBLE]
        }
        return out.concatToString()
    }

    /** The bytes of [hex] (either case); anything that is not whole hex pairs is refused. */
    fun decode(hex: String): ByteArray {
        require(hex.length % DIGITS_PER_BYTE == 0) { "identity key is not hex" }
        return ByteArray(hex.length / DIGITS_PER_BYTE) { index ->
            val high = nibble(hex[index * DIGITS_PER_BYTE])
            val low = nibble(hex[index * DIGITS_PER_BYTE + 1])
            ((high shl NIBBLE_BITS) or low).toByte()
        }
    }

    private fun nibble(char: Char): Int {
        val value = DIGITS.indexOf(char.lowercaseChar())
        require(value >= 0) { "identity key is not hex" }
        return value
    }
}
