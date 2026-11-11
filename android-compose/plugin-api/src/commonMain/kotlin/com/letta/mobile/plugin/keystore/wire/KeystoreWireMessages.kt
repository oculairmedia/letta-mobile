package com.letta.mobile.plugin.keystore.wire

import com.letta.mobile.plugin.keystore.PutOptions
import com.letta.mobile.plugin.keystore.SecretMetadata
import com.letta.mobile.plugin.keystore.SecretRef
import com.letta.mobile.plugin.keystore.SecretScope
import com.letta.mobile.plugin.keystore.SecretValue
import com.letta.mobile.plugin.keystore.WriteMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** The host named in `keystore.initialize`. */
@Serializable
public data class KeystoreHostInfo(
    /** The host program. */
    public val name: String,
    /** The host program's version. */
    public val version: String,
)

/**
 * `keystore.initialize` params: every contract major the host speaks, who the host is, and the
 * provider's non-secret settings (never a credential).
 */
@Serializable
public data class KeystoreInitializeParams(
    /** The contract majors the host offers. */
    public val contractVersions: List<Int>,
    /** Who the host is. */
    public val host: KeystoreHostInfo,
    /** The provider's non-secret configuration. */
    public val settings: JsonObject,
)

/** `keystore.initialize` result: the contract major the provider chose from the host's offer. */
@Serializable
public data class KeystoreInitializeResult(
    /** The chosen major. */
    public val contractVersion: Int,
)

/** `keystore.get` and `keystore.delete` params. */
@Serializable
public data class KeystoreRefParams(
    /** The slot (`get`) or item (`delete`). */
    public val ref: SecretRef,
)

/** `keystore.list` params. */
@Serializable
public data class KeystoreListParams(
    /** The scope to list. */
    public val scope: SecretScope,
)

/**
 * `keystore.unlock` params. [material] travels as base64 in this frame only; it is never in argv,
 * env or a log line. Not a data class: it prints no field.
 */
@Serializable
public class KeystoreUnlockParams(
    /** The host-owned unlock material. */
    @Serializable(with = SecretValueWireSerializer::class)
    public val material: SecretValue,
)

/** `keystore.get` result. [value] travels as base64 in this frame only. Not a data class: it prints no field. */
@Serializable
public class KeystoreGetResult(
    /** The slot's value. */
    @Serializable(with = SecretValueWireSerializer::class)
    public val value: SecretValue,
)

/** `keystore.put` params. [value] travels as base64 in this frame only. Not a data class: it prints no field. */
@Serializable
public class KeystorePutParams(
    /** The slot to write. */
    public val ref: SecretRef,
    /** The value to write. */
    @Serializable(with = SecretValueWireSerializer::class)
    public val value: SecretValue,
    /** How an existing or missing slot is treated. */
    public val mode: WriteMode,
    /** Non-secret notes, omitted when absent. */
    public val metadata: SecretMetadata? = null,
) {
    /** The SPI options these params carry. */
    public val options: PutOptions get() = PutOptions(mode, metadata)
}
