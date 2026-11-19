package com.letta.mobile.data.rpcwire

/**
 * Encoder-boundary secret input. Identity equality/hash semantics and a redacted [toString]
 * keep the value out of generated value semantics, snapshots, and diagnostics.
 */
class SecretWriteValue(private val value: String) {
    internal fun revealForEncoding(): String = value

    override fun toString(): String = "<secret withheld>"
}
