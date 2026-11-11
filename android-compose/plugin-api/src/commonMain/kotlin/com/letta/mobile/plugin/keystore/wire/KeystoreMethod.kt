package com.letta.mobile.plugin.keystore.wire

import com.letta.mobile.plugin.keystore.KeystoreOperation

/**
 * The keystore wire v1 method set: every method is a host-to-provider JSON-RPC request carried on
 * the LCP process framing (one message per line on stdin and stdout). [wire] is the method name,
 * [operation] the SPI operation it carries (null for protocol methods) and [deadlineMillis] how
 * long the host waits for the answer before failing it as `unavailable`.
 */
public enum class KeystoreMethod(
    /** The JSON-RPC method name. */
    public val wire: String,
    /** The SPI operation the method carries, or null for protocol plumbing. */
    public val operation: KeystoreOperation?,
    /** The host's deadline for the answer. */
    public val deadlineMillis: Long,
) {
    /** Version handshake; must come first. */
    INITIALIZE("keystore.initialize", null, 10_000),

    /** The provider's descriptor. */
    DESCRIBE("keystore.describe", null, 5_000),

    /** The provider's health. */
    HEALTH("keystore.health", KeystoreOperation.HEALTH, 5_000),

    /** Opens the backend with host-owned material. */
    UNLOCK("keystore.unlock", KeystoreOperation.UNLOCK, 30_000),

    /** Drops unlocked state. */
    LOCK("keystore.lock", KeystoreOperation.LOCK, 10_000),

    /** Reads one slot. */
    GET("keystore.get", KeystoreOperation.GET, 30_000),

    /** Writes one slot. */
    PUT("keystore.put", KeystoreOperation.PUT, 30_000),

    /** Lists item names in a scope. */
    LIST("keystore.list", KeystoreOperation.LIST, 30_000),

    /** Removes one item. */
    DELETE("keystore.delete", KeystoreOperation.DELETE, 30_000),

    /** Ends the session; the provider answers and exits. */
    SHUTDOWN("keystore.shutdown", null, 10_000),
    ;

    /** Lookup and wire constants. */
    public companion object {
        /** The JSON-RPC error code of every keystore failure; its `data` is a serialized `KeystoreError`. */
        public const val KEYSTORE_ERROR_CODE: Int = -32020

        /** The methods whose frames carry secret bytes, so a host never logs their params or results. */
        public val VALUE_CARRYING: Set<KeystoreMethod> = setOf(UNLOCK, GET, PUT)

        /** The method named [wire], or null for a name outside keystore wire v1. */
        public fun byWire(wire: String): KeystoreMethod? = entries.firstOrNull { it.wire == wire }
    }
}
