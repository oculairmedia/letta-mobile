package com.letta.mobile.data.model

/**
 * letta-mobile-ckmub.8: an `iroh://` backend address, read without dialing, so every client (and the
 * settings form) can say what is wrong with one before a transport sees it.
 *
 * The App Server prints two dialable forms (`appserver-serve-iroh`): a `Ticket:` line (an endpoint
 * ticket, `endpoint…` in base32, which also carries relay and direct addresses) and a `Short URL:`
 * line (`iroh://<64-hex endpoint id>@host:port[,host:port]`). Both resolve to the endpoint id, which
 * is all a browser client can use: it reaches the server through the relay and discovery, never by
 * direct UDP.
 */
data class IrohServerAddress(
    /** The server's endpoint id: 32 bytes as 64 lowercase hex characters. */
    val endpointIdHex: String,
    /** `host:port` hints from the short form or the ticket's text; empty when there are none. */
    val directAddresses: List<String> = emptyList(),
    /** Whether the address was given as a full endpoint ticket. */
    val fromTicket: Boolean = false,
) {
    /** The address as `iroh://<endpoint id>`, the form every Iroh binding accepts. */
    val endpointUrl: String get() = IROH_SCHEME + endpointIdHex

    companion object {
        private const val IROH_SCHEME = "iroh://"
        private const val ENDPOINT_ID_BYTES = 32
        private const val HEX_ID_LENGTH = ENDPOINT_ID_BYTES * 2
        private const val BASE32_ID_LENGTH = 52
        private const val TICKET_KIND = "endpoint"
        private const val TICKET_VARIANT = 0

        /** What a valid address looks like, for messages that reject one. */
        const val EXPECTED_FORMAT =
            "iroh://<64-hex endpoint id>, iroh://<endpoint id>@host:port, or iroh://<endpoint ticket>"

        /**
         * Reads [url] (with or without `iroh://`, tolerating the `https://iroh://` form an old saved
         * config can carry). Fails with an [IllegalArgumentException] whose message says what was
         * wrong and what is expected.
         */
        fun parse(url: String): IrohServerAddress {
            val body = url.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix(IROH_SCHEME)
                .trim()
                .trimEnd('/')
            require(body.isNotEmpty()) { "The Iroh address is empty. Expected $EXPECTED_FORMAT." }
            return if (looksLikeTicket(body)) parseTicket(body) else parseShortForm(body)
        }

        /** [parse]'s complaint about [url], or null when it is a valid Iroh address. */
        fun problem(url: String): String? = runCatching { parse(url) }.exceptionOrNull()?.message

        /** Whether [text] (no scheme) reads as an Iroh endpoint id or ticket rather than a host name. */
        fun looksLikeIrohIdentity(text: String): Boolean {
            val body = text.trim().substringBefore('@')
            return looksLikeTicket(body) || isHexId(body) || isBase32Id(body)
        }

        private fun looksLikeTicket(body: String): Boolean =
            body.startsWith(TICKET_KIND, ignoreCase = true) && body.length > BASE32_ID_LENGTH

        private fun parseShortForm(body: String): IrohServerAddress {
            val id = body.substringBefore('@').trim()
            val hints = body.substringAfter('@', missingDelimiterValue = "")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            hints.firstOrNull { ':' !in it }?.let { hint ->
                throw IllegalArgumentException("Invalid Iroh direct address '$hint': expected host:port.")
            }
            val hex = when {
                isHexId(id) -> id.lowercase()
                isBase32Id(id) -> Base32.decode(id)?.takeIf { it.size == ENDPOINT_ID_BYTES }?.toHex()
                else -> null
            } ?: throw IllegalArgumentException(
                "Invalid Iroh endpoint id (${id.length} characters): an endpoint id is 64 hex characters. " +
                    "Expected $EXPECTED_FORMAT.",
            )
            return IrohServerAddress(endpointIdHex = hex, directAddresses = hints)
        }

        /**
         * An endpoint ticket is `endpoint` + base32 of its postcard encoding, which starts with the
         * wire-format variant (0) and then the 32 raw bytes of the endpoint id.
         */
        private fun parseTicket(body: String): IrohServerAddress {
            val bytes = Base32.decode(body.substring(TICKET_KIND.length))
            require(bytes != null && bytes.size > ENDPOINT_ID_BYTES && bytes[0].toInt() == TICKET_VARIANT) {
                "Invalid Iroh endpoint ticket: it does not decode. Copy the whole Ticket line the server " +
                    "printed, or use its Short URL. Expected $EXPECTED_FORMAT."
            }
            val id = bytes.copyOfRange(1, 1 + ENDPOINT_ID_BYTES)
            return IrohServerAddress(endpointIdHex = id.toHex(), fromTicket = true)
        }

        private fun isHexId(text: String): Boolean =
            text.length == HEX_ID_LENGTH && text.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }

        private fun isBase32Id(text: String): Boolean =
            text.length == BASE32_ID_LENGTH && text.all { Base32.value(it) >= 0 }

        private fun ByteArray.toHex(): String = joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}

/** RFC 4648 base32 without padding, case-insensitive, as Iroh writes ids and tickets. */
private object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS_PER_CHAR = 5
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFF

    fun value(char: Char): Int = ALPHABET.indexOf(char.uppercaseChar())

    fun decode(text: String): ByteArray? {
        val out = ArrayList<Byte>(text.length * BITS_PER_CHAR / BITS_PER_BYTE)
        var buffer = 0
        var bits = 0
        for (char in text) {
            val digit = value(char)
            if (digit < 0) return null
            buffer = (buffer shl BITS_PER_CHAR) or digit
            bits += BITS_PER_CHAR
            if (bits >= BITS_PER_BYTE) {
                bits -= BITS_PER_BYTE
                out += ((buffer shr bits) and BYTE_MASK).toByte()
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}
