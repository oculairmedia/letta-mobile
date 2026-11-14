package com.letta.mobile.data.controller.node.iroh.secrets

import com.letta.mobile.data.secrets.request.SecretRequestLimits
import com.letta.mobile.data.secrets.wire.SecretValueBase64
import com.letta.mobile.plugin.keystore.SecretValue

/**
 * Turns the `value` field of a submission into a [SecretValue] at once (contract M1).
 *
 * The size is checked from the text's length and padding before any byte is allocated; the text
 * is decoded without making another string; the decoded array is zeroed as soon as the
 * [SecretValue] holds its own copy, on every path. What this cannot zero: [encoded] itself (the
 * JSON layer's immutable string) and the transport buffers it was parsed from.
 */
internal object SecretSubmission {
    /** The value [encoded] holds, or null when it is not canonical base64 of 1 to 65 536 bytes. */
    fun decode(encoded: String): SecretValue? {
        if (!withinCap(encoded)) return null
        val bytes = SecretValueBase64.decode(encoded) ?: return null
        return try {
            SecretValue.ofBytes(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /** Whether [encoded] can hold 1 to 65 536 bytes, read from its length and padding alone. */
    private fun withinCap(encoded: String): Boolean {
        if (encoded.length > SecretValueBase64.MAX_ENCODED_CHARS) return false
        return SecretValueBase64.decodedLength(encoded) in 1..SecretRequestLimits.MAX_VALUE_BYTES
    }
}
