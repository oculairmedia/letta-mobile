package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.canvas.compose.Sha256
import kotlin.io.encoding.Base64
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** RFC 7636 S256 verifier and challenge (letta-mobile-zxk2n.3). */
internal class Pkce private constructor(val verifier: String, val challenge: String) {
    override fun toString(): String = "Pkce(<redacted>)"

    companion object {
        const val METHOD = "S256"
        private const val VERIFIER_BYTES = 32
        private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        fun create(random: (Int) -> ByteArray): Pkce {
            val verifier = token(random(VERIFIER_BYTES))
            return Pkce(verifier, challengeOf(verifier))
        }

        /** S256: base64url(SHA-256(verifier)). */
        fun challengeOf(verifier: String): String = urlSafe.encode(Sha256.digest(verifier.encodeToByteArray()))

        /** Base64url without padding. */
        fun token(bytes: ByteArray): String = urlSafe.encode(bytes)
    }
}

/**
 * Random bytes for PKCE and state from [Uuid.random], which the standard
 * library documents as drawn from a cryptographically secure source on every
 * target. Each UUID gives 122 random bits; enough are concatenated to cover
 * [size] bytes.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    var filled = 0
    while (filled < size) {
        val chunk = Uuid.random().toByteArray()
        val take = minOf(chunk.size, size - filled)
        chunk.copyInto(out, destinationOffset = filled, endIndex = take)
        filled += take
    }
    return out
}
