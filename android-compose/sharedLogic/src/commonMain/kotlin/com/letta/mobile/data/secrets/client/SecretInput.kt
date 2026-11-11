package com.letta.mobile.data.secrets.client

/**
 * The secret a person typed into the secure form, on its way to the host. The client-side twin of
 * the keystore `SecretValue` (which is host-only: `:plugin-api` does not ship to Android or the
 * web), built to the same rules so an accidental leak is impossible:
 *
 * - [toString] is always [REDACTED], whatever the value holds (its length included).
 * - It is not a data class, not serializable and has no equality but identity.
 * - The bytes are reachable only through [revealSecretBytes], which lends a copy that is zeroed
 *   when the block ends.
 * - [wipe] zeroes the bytes for good; whoever holds the value wipes it once the call it was made
 *   for returns, on every path.
 *
 * It is not safe for concurrent [wipe] and reveal.
 */
class SecretInput private constructor(private val bytes: ByteArray) {
    private var wiped: Boolean = false

    /** Whether [wipe] ran; a wiped value can no longer be revealed. */
    val isWiped: Boolean get() = wiped

    /** Whether the value holds no bytes (a client never sends an empty secret). */
    val isEmpty: Boolean get() = bytes.isEmpty()

    /**
     * Lends a copy of the secret bytes to [block] and zeroes the copy when [block] returns or
     * throws. The awkward name is intended: every read of a secret is explicit at the call site.
     *
     * @throws IllegalStateException when the value was wiped (a programming error).
     */
    fun <R> revealSecretBytes(block: (ByteArray) -> R): R {
        check(!wiped) { WIPED }
        val loan = bytes.copyOf()
        try {
            return block(loan)
        } finally {
            loan.fill(0)
        }
    }

    /** Zeroes the bytes; afterwards [isWiped] is true and reveals throw. Calling it twice is harmless. */
    fun wipe() {
        bytes.fill(0)
        wiped = true
    }

    /** Always [REDACTED]: a secret never prints. */
    override fun toString(): String = REDACTED

    /** Factories and the fixed redaction text. */
    companion object {
        /** What every [SecretInput.toString] answers. */
        const val REDACTED: String = "SecretInput(redacted)"

        private const val WIPED: String = "The secret input was wiped"

        /** A value holding a copy of [bytes]; the caller still owns [bytes] and zeroes it when done. */
        fun ofBytes(bytes: ByteArray): SecretInput = SecretInput(bytes.copyOf())

        /** A value that takes [bytes] over without copying: the caller must not keep or reuse the array. */
        internal fun adopt(bytes: ByteArray): SecretInput = SecretInput(bytes)
    }
}
