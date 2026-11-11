package com.letta.mobile.plugin.keystore

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

private val PROVIDER_ID_GRAMMAR = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")
private val SEGMENT_GRAMMAR = Regex("[A-Za-z0-9_.-]+")
private val DOT_SEGMENTS = setOf(".", "..")

private fun isSegment(text: String): Boolean = SEGMENT_GRAMMAR.matches(text) && text !in DOT_SEGMENTS

/**
 * A keystore provider's id, `[a-z0-9]+([.-][a-z0-9]+)*` and at most
 * [KeystoreSpi.MAX_PROVIDER_ID_LENGTH] characters. The type holds any text so untrusted input can
 * be represented and refused with a result; check [isWellFormed] before trusting it.
 */
@Serializable
@JvmInline
public value class KeystoreProviderId(
    /** The id text. */
    public val value: String,
) {
    /** Whether [value] follows the provider id grammar. */
    public val isWellFormed: Boolean
        get() = value.length <= KeystoreSpi.MAX_PROVIDER_ID_LENGTH && PROVIDER_ID_GRAMMAR.matches(value)

    /** The id text itself. */
    override fun toString(): String = value
}

/**
 * The item part of a [SecretRef]: 1 to [KeystoreSpi.MAX_PATH_SEGMENTS] segments joined by `/`,
 * each `[A-Za-z0-9_.-]+` and neither `.` nor `..`, at most [KeystoreSpi.MAX_PATH_LENGTH]
 * characters. A path is a name, never a value.
 */
@Serializable
@JvmInline
public value class SecretPath(
    /** The path text. */
    public val value: String,
) {
    /** Whether [value] follows the path grammar. */
    public val isWellFormed: Boolean
        get() = value.length <= KeystoreSpi.MAX_PATH_LENGTH && segmentsWellFormed()

    /** Whether [path] equals this path or lies under it by whole segments (`a/b` covers `a/b/c`, not `a/bc`). */
    public fun covers(path: SecretPath): Boolean = path.value == value || path.value.startsWith("$value/")

    /** The path text itself. */
    override fun toString(): String = value

    private fun segmentsWellFormed(): Boolean {
        val segments = value.split('/')
        return segments.size <= KeystoreSpi.MAX_PATH_SEGMENTS && segments.all(::isSegment)
    }
}

/**
 * A named slot of an item, `[A-Za-z0-9_.-]{1,64}` and neither `.` nor `..`. A [SecretRef] without
 * a field addresses the item's default slot; each field is an independent slot.
 */
@Serializable
@JvmInline
public value class SecretField(
    /** The field name. */
    public val value: String,
) {
    /** Whether [value] follows the field grammar. */
    public val isWellFormed: Boolean
        get() = value.length <= KeystoreSpi.MAX_FIELD_LENGTH && isSegment(value)

    /** The field name itself. */
    override fun toString(): String = value
}

/**
 * Names one secret slot: never the value. Two refs are equal exactly when [provider], [path] and
 * [field] are equal as strings (no case folding or normalization), an absent field matching only
 * an absent field. On the wire it is `{provider, path, field?}`.
 */
@Serializable
public data class SecretRef(
    /** The provider that holds the slot. */
    public val provider: KeystoreProviderId,
    /** The item inside the provider. */
    public val path: SecretPath,
    /** The slot of the item; null for the item's default slot. */
    public val field: SecretField? = null,
) {
    /** Whether every component follows its grammar. */
    public val isWellFormed: Boolean get() = itemWellFormed() && fieldWellFormed()

    /** This ref without its field: the item as `list` and `delete` name it. */
    public val item: SecretRef get() = copy(field = null)

    /**
     * Why a provider whose id is [expected] must refuse this ref ([RefProblem.MALFORMED] or
     * [RefProblem.PROVIDER_MISMATCH]), or null when it may serve it.
     */
    public fun problemFor(expected: KeystoreProviderId): RefProblem? = when {
        !isWellFormed -> RefProblem.MALFORMED
        provider != expected -> RefProblem.PROVIDER_MISMATCH
        else -> null
    }

    /** The text form `<provider>:<path>`, plus `#<field>` when a field is present. */
    override fun toString(): String = field?.let { "$provider:$path#$it" } ?: "$provider:$path"

    private fun itemWellFormed(): Boolean = provider.isWellFormed && path.isWellFormed

    private fun fieldWellFormed(): Boolean = field?.isWellFormed ?: true
}

/**
 * The refs a `list` covers: every item of [provider] when [prefix] is null, otherwise the items at
 * or under [prefix] by whole segments (see [SecretPath.covers]).
 */
@Serializable
public data class SecretScope(
    /** The provider to list. */
    public val provider: KeystoreProviderId,
    /** The path the listed items lie at or under; null for the whole provider. */
    public val prefix: SecretPath? = null,
) {
    /** Whether [ref] lies in this scope. */
    public fun contains(ref: SecretRef): Boolean = ref.provider == provider && prefix?.covers(ref.path) != false

    /**
     * Why a provider whose id is [expected] must refuse this scope ([RefProblem.MALFORMED] or
     * [RefProblem.PROVIDER_MISMATCH]), or null when it may serve it.
     */
    public fun problemFor(expected: KeystoreProviderId): RefProblem? = when {
        !isWellFormed() -> RefProblem.MALFORMED
        provider != expected -> RefProblem.PROVIDER_MISMATCH
        else -> null
    }

    /** The text form `<provider>:`, plus `<prefix>/` when a prefix is present. */
    override fun toString(): String = prefix?.let { "$provider:$it/" } ?: "$provider:"

    private fun isWellFormed(): Boolean = provider.isWellFormed && prefix?.isWellFormed != false
}

/**
 * What `list` answers: item names only, never values. [entries] are distinct refs without a field,
 * all in the requested scope, sorted by path, at most [KeystoreSpi.MAX_LIST_ENTRIES].
 */
@Serializable
public data class SecretListing(
    /** The listed items. */
    public val entries: List<SecretRef>,
    /** Whether the scope holds more items than [entries] lists. */
    public val truncated: Boolean,
)
