package com.letta.mobile.plugin.keystore

/**
 * The only in-process carrier of secret bytes. It is built to make an accidental leak impossible:
 *
 * - [toString] is always [REDACTED], whatever the value holds (its length included), so string
 *   templates, loggers, assertion messages and crash reports print no secret.
 * - It is deliberately not a data class, not `java.io.Serializable` and carries no kotlinx
 *   serializer of its own: only the keystore wire DTOs encode it, field by field.
 * - Equality and hash are identity; compare contents with [contentEqualsConstantTime].
 * - The bytes are reachable only through [revealSecretBytes], which lends a copy that is zeroed
 *   when the block ends. Do not keep, copy or convert the lent array beyond the block.
 * - [wipe] zeroes the bytes for good; reading a wiped value is a programming error.
 *
 * Instances are not safe for concurrent [wipe] and reveal; the owner wipes once it is done.
 */
public class SecretValue private constructor(private val bytes: ByteArray) {
    private var wiped: Boolean = false

    /** Whether [wipe] ran; a wiped value can no longer be revealed or compared. */
    public val isWiped: Boolean get() = wiped

    /**
     * Why this value is not a valid secret ([ValueProblem.EMPTY] or [ValueProblem.TOO_LARGE]), or
     * null when it is. Lets a provider validate a value without revealing it.
     */
    public val valueProblem: ValueProblem?
        get() = when {
            bytes.isEmpty() -> ValueProblem.EMPTY
            bytes.size > KeystoreSpi.MAX_VALUE_BYTES -> ValueProblem.TOO_LARGE
            else -> null
        }

    /**
     * Lends a copy of the secret bytes to [block] and zeroes the copy when [block] returns or
     * throws. The awkward name is intended: every read of a secret is explicit at the call site.
     *
     * @throws IllegalStateException when the value was wiped (a programming error).
     */
    public fun <R> revealSecretBytes(block: (ByteArray) -> R): R {
        check(!wiped) { WIPED }
        val loan = bytes.copyOf()
        try {
            return block(loan)
        } finally {
            loan.fill(0)
        }
    }

    /**
     * Whether [other] holds the same bytes, comparing every byte of the longer value so the time
     * taken does not depend on where the values differ.
     *
     * @throws IllegalStateException when either value was wiped (a programming error).
     */
    public fun contentEqualsConstantTime(other: SecretValue): Boolean {
        check(!wiped && !other.wiped) { WIPED }
        var difference = bytes.size xor other.bytes.size
        val length = maxOf(bytes.size, other.bytes.size)
        for (index in 0 until length) {
            difference = difference or (byteAt(index) xor other.byteAt(index))
        }
        return difference == 0
    }

    /** Zeroes the bytes; afterwards [isWiped] is true and reveals throw. Calling it twice is harmless. */
    public fun wipe() {
        bytes.fill(0)
        wiped = true
    }

    /** Always [REDACTED]: a secret value never prints. */
    override fun toString(): String = REDACTED

    private fun byteAt(index: Int): Int = bytes.getOrElse(index) { 0 }.toInt()

    /** Factories and the fixed redaction text. */
    public companion object {
        /** What every [SecretValue.toString] answers. */
        public const val REDACTED: String = "SecretValue(redacted)"

        private const val WIPED: String = "The secret value was wiped"

        /**
         * A value holding a copy of [bytes]. The caller still owns [bytes] and should zero it once
         * it is no longer needed.
         */
        public fun ofBytes(bytes: ByteArray): SecretValue = SecretValue(bytes.copyOf())
    }
}
