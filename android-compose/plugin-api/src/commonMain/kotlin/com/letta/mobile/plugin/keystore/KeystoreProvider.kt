package com.letta.mobile.plugin.keystore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The keystore provider SPI: a module that stores secrets in a backend of its author's choice.
 * The same contract runs in process (this interface) and out of process (the `keystore.*` wire
 * methods of `com.letta.mobile.plugin.keystore.wire`); `docs/reference/contracts/keystore-v1.md`
 * is normative.
 *
 * Rules every implementation keeps:
 *
 * - Expected failures are [KeystoreResult.Failure] values with a stable [KeystoreErrorCode]; an
 *   implementation does not throw for them.
 * - Checks run in this order, first match wins: [KeystoreError.Unsupported],
 *   [KeystoreError.InvalidRef], [KeystoreError.InvalidValue], [KeystoreError.Unavailable],
 *   [KeystoreError.Locked], then backend outcomes.
 * - Fail closed: while the provider is not ready, data operations fail; no operation ever answers
 *   an empty, default or cached value in place of the real one.
 * - A value never appears in a name, listing, error, diagnostic, log or exception.
 *
 * Grants, routing and audit are the host's; a provider serves whatever the host asks of it.
 */
public interface KeystoreProvider {
    /** Who the provider is and what it can do. Static: the same answer every call, no I/O, valid while locked or down. */
    public fun describe(): KeystoreDescriptor

    /** Whether data operations can succeed now; never throws, and holds no value in its diagnostic. */
    public suspend fun health(): KeystoreHealth

    /**
     * Opens the backend with host-owned [material] (a master password, an API key). Idempotent
     * when already open; wrong material answers [KeystoreError.Denied]. A provider whose
     * descriptor does not set [KeystoreDescriptor.needsUnlock] answers [KeystoreError.Unsupported].
     * The provider must not keep [material] beyond what its backend session needs.
     */
    public suspend fun unlock(material: SecretValue): KeystoreResult<Unit> =
        KeystoreResult.Failure(KeystoreError.Unsupported(KeystoreOperation.UNLOCK))

    /** Drops any unlocked state; idempotent. Unsupported unless [KeystoreDescriptor.needsUnlock]. */
    public suspend fun lock(): KeystoreResult<Unit> =
        KeystoreResult.Failure(KeystoreError.Unsupported(KeystoreOperation.LOCK))

    /**
     * The current value of [ref]'s slot, as a fresh [SecretValue] the caller owns and wipes.
     * A missing slot answers [KeystoreError.NotFound] naming the same ref.
     */
    public suspend fun get(ref: SecretRef): KeystoreResult<SecretValue>

    /**
     * Writes [value] to [ref]'s slot as [options] say ([WriteMode] and optional metadata). The
     * provider copies what it keeps; the caller still owns and wipes [value].
     */
    public suspend fun put(ref: SecretRef, value: SecretValue, options: PutOptions = PutOptions()): KeystoreResult<Unit>

    /** The item names in [scope]: never values (see [SecretListing]). */
    public suspend fun list(scope: SecretScope): KeystoreResult<SecretListing>

    /**
     * Removes the item [ref] names and all its slots. [ref] must have no field
     * ([RefProblem.FIELD_NOT_ALLOWED]); a missing item answers [KeystoreError.NotFound].
     */
    public suspend fun delete(ref: SecretRef): KeystoreResult<Unit>
}

/** How `put` treats an existing or missing slot; on the wire its lowercase name. */
@Serializable
public enum class WriteMode {
    /** Write only a missing slot; an existing one answers [KeystoreError.Conflict]. */
    @SerialName("create")
    CREATE,

    /** Write only an existing slot; a missing one answers [KeystoreError.NotFound]. */
    @SerialName("replace")
    REPLACE,

    /** Write either way. */
    @SerialName("upsert")
    UPSERT,
}

/** The options of a `put`: its [mode] and optional, non-secret [metadata]. */
public data class PutOptions(
    /** How an existing or missing slot is treated. */
    public val mode: WriteMode = WriteMode.UPSERT,
    /** Non-secret notes a provider may store or ignore. */
    public val metadata: SecretMetadata? = null,
)
