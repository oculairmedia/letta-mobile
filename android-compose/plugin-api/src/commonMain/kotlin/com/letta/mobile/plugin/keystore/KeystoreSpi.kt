package com.letta.mobile.plugin.keystore

/**
 * The version and limits of the keystore provider contract (`docs/reference/contracts/keystore-v1.md`).
 *
 * The contract is versioned like the canvas plugin SPI: [CONTRACT_VERSION] is the major a host and
 * a provider agree on (in process by the `:plugin-api` a provider is built against, out of process
 * through `keystore.initialize`). A minor adds optional fields or members with defaults; a major
 * changes error codes, required fields or semantics.
 */
public object KeystoreSpi {
    /** The keystore contract major this SPI implements. */
    public const val CONTRACT_VERSION: Int = 1

    /** The largest secret value, in bytes. */
    public const val MAX_VALUE_BYTES: Int = 64 * 1024

    /** The most entries one `list` answers; more set [SecretListing.truncated]. */
    public const val MAX_LIST_ENTRIES: Int = 1_000

    /** The longest [KeystoreProviderId], in characters. */
    public const val MAX_PROVIDER_ID_LENGTH: Int = 64

    /** The longest [SecretPath], in characters. */
    public const val MAX_PATH_LENGTH: Int = 256

    /** The most `/`-separated segments in a [SecretPath]. */
    public const val MAX_PATH_SEGMENTS: Int = 16

    /** The longest [SecretField], in characters. */
    public const val MAX_FIELD_LENGTH: Int = 64

    /** The longest [SecretDescription], in characters. */
    public const val MAX_DESCRIPTION_LENGTH: Int = 256

    /** The longest [ProviderDiagnostic], in characters. */
    public const val MAX_DIAGNOSTIC_LENGTH: Int = 200
}
