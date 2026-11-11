package com.letta.mobile.plugin.keystore

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/** The outcome of a provider operation: a [Success] value or a [Failure] with a stable error code. */
public sealed interface KeystoreResult<out T> {
    /** The operation succeeded with [value] (a [SecretValue] prints redacted). */
    public data class Success<out T>(
        /** What the operation answered. */
        public val value: T,
    ) : KeystoreResult<T>

    /** The operation failed with [error]; expected failures are values, not exceptions. */
    public data class Failure(
        /** Why it failed. */
        public val error: KeystoreError,
    ) : KeystoreResult<Nothing>

    /** Shared results. */
    public companion object {
        /** The success of an operation that answers nothing. */
        public val Done: KeystoreResult<Unit> = Success(Unit)
    }
}

/** The success value, or null for a failure. */
public fun <T> KeystoreResult<T>.valueOrNull(): T? = (this as? KeystoreResult.Success<T>)?.value

/** The failure's error, or null for a success. */
public fun KeystoreResult<*>.errorOrNull(): KeystoreError? = (this as? KeystoreResult.Failure)?.error

/** The stable codes of [KeystoreError]; their wire spelling is the lowercase name. New codes need a new major. */
@Serializable
public enum class KeystoreErrorCode {
    /** The slot or item does not exist. */
    @SerialName("not_found")
    NOT_FOUND,

    /** The backend refused, or unlock material was wrong; a host also uses it for grant refusals. */
    @SerialName("denied")
    DENIED,

    /** The provider or backend is unreachable, unhealthy, not running or timed out. */
    @SerialName("unavailable")
    UNAVAILABLE,

    /** The provider needs `unlock` first. */
    @SerialName("locked")
    LOCKED,

    /** The operation's capability is not declared. */
    @SerialName("unsupported")
    UNSUPPORTED,

    /** The ref or scope is malformed, names another provider, or carries a field where none is allowed. */
    @SerialName("invalid_ref")
    INVALID_REF,

    /** The value is empty or too large. */
    @SerialName("invalid_value")
    INVALID_VALUE,

    /** `put` with [WriteMode.CREATE] found an existing slot. */
    @SerialName("conflict")
    CONFLICT,

    /** The provider failed unexpectedly. */
    @SerialName("internal")
    INTERNAL,
}

/** Why a ref or scope is refused with [KeystoreError.InvalidRef]. */
@Serializable
public enum class RefProblem {
    /** A component breaks its grammar. */
    @SerialName("malformed")
    MALFORMED,

    /** The ref names another provider. */
    @SerialName("provider_mismatch")
    PROVIDER_MISMATCH,

    /** The operation takes an item, not a field (`delete`). */
    @SerialName("field_not_allowed")
    FIELD_NOT_ALLOWED,
}

/** Why a value is refused with [KeystoreError.InvalidValue]. */
@Serializable
public enum class ValueProblem {
    /** The value has no bytes; an empty value is never a secret. */
    @SerialName("empty")
    EMPTY,

    /** The value exceeds [KeystoreSpi.MAX_VALUE_BYTES]. */
    @SerialName("too_large")
    TOO_LARGE,
}

/**
 * A provider failure. Each subtype carries only names and codes, and its [message] is a fixed
 * template built from them, so no free text (let alone a value) can enter one; the only operator
 * text is an optional [ProviderDiagnostic]. On the wire it is the `data` of JSON-RPC error
 * `-32020`: `{"code": <code>, ...fields}`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("code")
public sealed class KeystoreError {
    /** The stable code. */
    public abstract val code: KeystoreErrorCode

    /** A fixed, value-free sentence for people; act on [code], not on this text. */
    public abstract val message: String

    /** `KeystoreError(<code>: <message>)`, which holds names only. */
    final override fun toString(): String = "KeystoreError(${code.name.lowercase()}: $message)"

    /** No slot or item at [ref]. */
    @Serializable
    @SerialName("not_found")
    public data class NotFound(
        /** The ref that was asked for. */
        public val ref: SecretRef,
    ) : KeystoreError() {
        /** Always [KeystoreErrorCode.NOT_FOUND]. */
        override val code: KeystoreErrorCode get() = KeystoreErrorCode.NOT_FOUND
        /** The fixed template for this code. */
        override val message: String get() = "No secret at $ref"
    }

    /** The backend refused [operation], or `unlock` material was wrong. */
    @Serializable
    @SerialName("denied")
    public data class Denied(
        /** The refused operation. */
        public val operation: KeystoreOperation,
    ) : KeystoreError() {
        /** Always [KeystoreErrorCode.DENIED]. */
        override val code: KeystoreErrorCode get() = KeystoreErrorCode.DENIED
        /** The fixed template for this code. */
        override val message: String get() = "The keystore refused ${operation.name.lowercase()}"
    }

    /** The provider or its backend cannot serve now; [detail] is optional operator text. */
    @Serializable
    @SerialName("unavailable")
    public data class Unavailable(
        /** Value-free operator text. */
        public val detail: ProviderDiagnostic? = null,
    ) : KeystoreError() {
        /** Always [KeystoreErrorCode.UNAVAILABLE]. */
        override val code: KeystoreErrorCode get() = KeystoreErrorCode.UNAVAILABLE
        /** The fixed template for this code. */
        override val message: String get() = "The keystore provider is unavailable"
    }

    /** The provider needs `unlock` first. */
    @Serializable
    @SerialName("locked")
    public data object Locked : KeystoreError() {
        /** Always [KeystoreErrorCode.LOCKED]. */
        override val code: KeystoreErrorCode get() = KeystoreErrorCode.LOCKED
        /** The fixed template for this code. */
        override val message: String get() = "The keystore provider is locked"
    }

    /** The provider does not declare the capability [operation] needs. */
    @Serializable
    @SerialName("unsupported")
    public data class Unsupported(
        /** The refused operation. */
        public val operation: KeystoreOperation,
    ) : KeystoreError() {
        /** Always [KeystoreErrorCode.UNSUPPORTED]. */
        override val code: KeystoreErrorCode get() = KeystoreErrorCode.UNSUPPORTED
        /** The fixed template for this code. */
        override val message: String get() = "The keystore provider does not support ${operation.name.lowercase()}"
    }

    /** The ref or scope is refused for [refProblem]. */
    @Serializable
    @SerialName("invalid_ref")
    public data class InvalidRef(
        /** Why it is refused. */
        public val refProblem: RefProblem,
    ) : KeystoreError() {
        /** Always [KeystoreErrorCode.INVALID_REF]. */
        override val code: KeystoreErrorCode get() = KeystoreErrorCode.INVALID_REF
        /** The fixed template for this code. */
        override val message: String get() = "Invalid secret reference: ${refProblem.name.lowercase()}"
    }

    /** The value is refused for [valueProblem]. */
    @Serializable
    @SerialName("invalid_value")
    public data class InvalidValue(
        /** Why it is refused. */
        public val valueProblem: ValueProblem,
    ) : KeystoreError() {
        /** Always [KeystoreErrorCode.INVALID_VALUE]. */
        override val code: KeystoreErrorCode get() = KeystoreErrorCode.INVALID_VALUE
        /** The fixed template for this code. */
        override val message: String get() = "Invalid secret value: ${valueProblem.name.lowercase()}"
    }

    /** A `put` with [WriteMode.CREATE] found a slot at [ref]. */
    @Serializable
    @SerialName("conflict")
    public data class Conflict(
        /** The existing slot. */
        public val ref: SecretRef,
    ) : KeystoreError() {
        /** Always [KeystoreErrorCode.CONFLICT]. */
        override val code: KeystoreErrorCode get() = KeystoreErrorCode.CONFLICT
        /** The fixed template for this code. */
        override val message: String get() = "A secret already exists at $ref"
    }

    /** The provider failed unexpectedly; [detail] is optional operator text. */
    @Serializable
    @SerialName("internal")
    public data class Internal(
        /** Value-free operator text. */
        public val detail: ProviderDiagnostic? = null,
    ) : KeystoreError() {
        /** Always [KeystoreErrorCode.INTERNAL]. */
        override val code: KeystoreErrorCode get() = KeystoreErrorCode.INTERNAL
        /** The fixed template for this code. */
        override val message: String get() = "The keystore provider failed"
    }
}
