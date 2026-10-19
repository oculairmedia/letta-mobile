package com.letta.mobile.data.pairing

/**
 * The scannable pairing link: one URI that a phone camera
 * can hand to the app and that the in-app scanner also accepts.
 *
 * ```
 * meridian://pair?qr=letta-qr-v1.<base64url>&dial=<percent-encoded dial address>
 * ```
 *
 * - `qr` is the canonical `letta-qr-v1` envelope (docs/reference/qr-pairing-protocol.md
 *   §5/§7), unchanged: node id + one-time secret + expiry + signature.
 * - `dial` is a non-secret reachability hint — the host's Iroh ticket or
 *   `<node-id>@host:port` — because the v1 envelope deliberately carries no
 *   address (§5.3) and bare-NodeId discovery is not guaranteed. The client
 *   dials it but still requires the QUIC peer to BE `node_id` from the signed
 *   envelope, so a tampered hint can only cause a failed dial, never a pairing
 *   with the wrong host.
 *
 * `letta://pair?…` is accepted as an alias (the Android app's original scheme).
 * The link contains the invite secret: never log it.
 */
data class PairLink(val qrInvite: String, val dial: String?) {
    fun encode(): String = buildString {
        append(CANONICAL_PREFIX).append("qr=").append(percentEncode(qrInvite))
        dial?.takeIf { it.isNotBlank() }?.let { append("&dial=").append(percentEncode(it)) }
    }

    override fun toString(): String = "PairLink(<redacted>, dial=${dial != null})"

    companion object {
        const val QR_SCHEME_PREFIX = "letta-qr-v1."
        private const val CANONICAL_PREFIX = "meridian://pair?"
        private val ACCEPTED_PREFIXES = listOf("meridian://pair?", "letta://pair?", "meridian://pair/?", "letta://pair/?")

        /**
         * Parse a scanned/opened value: a pairing link or a bare `letta-qr-v1.`
         * envelope (as printed by older hosts). Returns null for anything else.
         */
        fun parse(raw: String): PairLink? {
            val value = raw.trim()
            if (value.startsWith(QR_SCHEME_PREFIX)) return PairLink(qrInvite = value, dial = null)
            val prefix = ACCEPTED_PREFIXES.firstOrNull { value.startsWith(it, ignoreCase = true) } ?: return null
            val params = parseQuery(value.substring(prefix.length))
            val qr = params["qr"]?.takeIf { it.startsWith(QR_SCHEME_PREFIX) } ?: return null
            return PairLink(qrInvite = qr, dial = params["dial"]?.takeIf { it.isNotBlank() })
        }

        /** True when [raw] looks like a pairing link (cheap pre-check for deep-link routing). */
        fun isPairLink(raw: String): Boolean = parse(raw) != null

        private fun parseQuery(query: String): Map<String, String> =
            query.substringBefore('#').split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', missingDelimiterValue = "")
                if (key.isEmpty()) return@mapNotNull null
                val decoded = percentDecode(pair.substringAfter('=')) ?: return@mapNotNull null
                key to decoded
            }.toMap()

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        private const val HEX = "0123456789ABCDEF"

        internal fun percentEncode(value: String): String = buildString {
            value.encodeToByteArray().forEach { byte ->
                val c = (byte.toInt() and 0xFF).toChar()
                if (c in UNRESERVED) {
                    append(c)
                } else {
                    val b = byte.toInt() and 0xFF
                    append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
                }
            }
        }

        /** Decode `%XX` escapes (and `+` as space); null on a malformed escape. */
        internal fun percentDecode(value: String): String? {
            val bytes = ArrayList<Byte>(value.length)
            var i = 0
            while (i < value.length) {
                val token = decodeTokenAt(value, i) ?: return null
                bytes += token.bytes
                i += token.span
            }
            return bytes.toByteArray().decodeToString()
        }

        private class DecodedToken(val bytes: List<Byte>, val span: Int)

        private fun decodeTokenAt(value: String, index: Int): DecodedToken? {
            val c = value[index]
            if (c != '%') return DecodedToken((if (c == '+') " " else c.toString()).encodeToByteArray().toList(), 1)
            val hex = value.substring(index + 1).take(2)
            val byte = hex.takeIf { it.length == 2 && it.all { d -> d.digitToIntOrNull(16) != null } }?.toInt(16)
                ?: return null
            return DecodedToken(listOf(byte.toByte()), 3)
        }
    }
}
