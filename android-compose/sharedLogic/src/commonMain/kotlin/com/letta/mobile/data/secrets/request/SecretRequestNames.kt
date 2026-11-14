package com.letta.mobile.data.secrets.request

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

private val KEYSTORE_GRAMMAR = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")
private val SEGMENT_GRAMMAR = Regex("[A-Za-z0-9_.-]+")
private val REQUEST_ID_GRAMMAR = Regex("sr_[A-Za-z0-9_-]{43}")
private val ENDPOINT_GRAMMAR = Regex("[A-Za-z0-9]+")
private val DOT_SEGMENTS = setOf(".", "..")

private fun isSegment(text: String): Boolean = SEGMENT_GRAMMAR.matches(text) && text !in DOT_SEGMENTS

/**
 * The host-generated, unguessable id of one secret request: `sr_` followed by 43 base64url
 * characters (32 random bytes). Ids are never reused, so an unknown id and a pruned one are the
 * same to every caller. The type holds any text so untrusted input can be represented and refused.
 */
@Serializable
@JvmInline
value class SecretRequestId(
    /** The id text. */
    val value: String,
) {
    /** Whether [value] follows the id grammar. */
    val isWellFormed: Boolean get() = REQUEST_ID_GRAMMAR.matches(value)

    /** A bounded label safe for logs and telemetry: the prefix and the first eight id characters. */
    val logLabel: String get() = value.take(LOG_LABEL_LENGTH)

    /** The id text itself. */
    override fun toString(): String = value

    /** Grammar constants. */
    companion object {
        /** The prefix every id starts with. */
        const val PREFIX: String = "sr_"

        /** How many random bytes an id encodes. */
        const val RANDOM_BYTES: Int = 32

        private const val LOG_LABEL_LENGTH = 11
    }
}

/**
 * The keystore that will hold the secret, spelled like a keystore provider id:
 * `[a-z0-9]+([.-][a-z0-9]+)*`, at most [SecretRequestLimits.MAX_KEYSTORE_LENGTH] characters.
 * The host fixes it when the request is created; the agent never chooses it.
 */
@Serializable
@JvmInline
value class SecretKeystoreName(
    /** The keystore name. */
    val value: String,
) {
    /** Whether [value] follows the keystore name grammar. */
    val isWellFormed: Boolean
        get() = value.length <= SecretRequestLimits.MAX_KEYSTORE_LENGTH && KEYSTORE_GRAMMAR.matches(value)

    /** The keystore name itself. */
    override fun toString(): String = value
}

/**
 * The vault item a request writes, with the keystore path grammar: 1 to
 * [SecretRequestLimits.MAX_ITEM_SEGMENTS] segments joined by `/`, each `[A-Za-z0-9_.-]+` and
 * neither `.` nor `..`, at most [SecretRequestLimits.MAX_ITEM_LENGTH] characters. A name, never a value.
 */
@Serializable
@JvmInline
value class VaultItemName(
    /** The item name. */
    val value: String,
) {
    /** Whether [value] follows the item grammar. */
    val isWellFormed: Boolean
        get() = value.length <= SecretRequestLimits.MAX_ITEM_LENGTH && segmentsWellFormed()

    /** Whether [item] equals this name or lies under it by whole segments. */
    fun covers(item: VaultItemName): Boolean = item.value == value || item.value.startsWith("$value/")

    /** The item name itself. */
    override fun toString(): String = value

    private fun segmentsWellFormed(): Boolean {
        val segments = value.split('/')
        return segments.size <= SecretRequestLimits.MAX_ITEM_SEGMENTS && segments.all(::isSegment)
    }
}

/**
 * The field of the item a request writes, with the keystore field grammar:
 * `[A-Za-z0-9_.-]{1,64}` and neither `.` nor `..`.
 */
@Serializable
@JvmInline
value class VaultFieldName(
    /** The field name. */
    val value: String,
) {
    /** Whether [value] follows the field grammar. */
    val isWellFormed: Boolean get() = value.length <= SecretRequestLimits.MAX_FIELD_LENGTH && isSegment(value)

    /** The field name itself. */
    override fun toString(): String = value
}

/**
 * The verified transport identity of the device a human principal acted from (an Iroh endpoint
 * id taken from the serving connection, never from request parameters): ASCII letters and digits,
 * at most [SecretRequestLimits.MAX_ENDPOINT_LENGTH] characters.
 */
@Serializable
@JvmInline
value class VerifiedEndpointId(
    /** The endpoint id text. */
    val value: String,
) {
    /** Whether [value] follows the endpoint grammar. */
    val isWellFormed: Boolean
        get() = value.length <= SecretRequestLimits.MAX_ENDPOINT_LENGTH && ENDPOINT_GRAMMAR.matches(value)

    /** The endpoint id text itself. */
    override fun toString(): String = value
}

/** The fixed limits of the secret request contract. */
object SecretRequestLimits {
    /** The longest purpose, in characters, after trimming. */
    const val MAX_PURPOSE_LENGTH: Int = 280

    /** The longest keystore name, in characters (the keystore provider id limit). */
    const val MAX_KEYSTORE_LENGTH: Int = 64

    /** The longest item name, in characters (the keystore path limit). */
    const val MAX_ITEM_LENGTH: Int = 256

    /** The most `/`-separated segments in an item name (the keystore path limit). */
    const val MAX_ITEM_SEGMENTS: Int = 16

    /** The longest field name, in characters (the keystore field limit). */
    const val MAX_FIELD_LENGTH: Int = 64

    /** The longest verified endpoint id, in characters. */
    const val MAX_ENDPOINT_LENGTH: Int = 128

    /** The largest secret value, in bytes (the keystore value limit). */
    const val MAX_VALUE_BYTES: Int = 64 * 1024

    /** The most requests one agent may have pending at once. */
    const val MAX_PENDING_PER_AGENT: Int = 8

    /** The most requests one agent may create within [CREATION_WINDOW_MS] (a sliding window). */
    const val MAX_CREATED_PER_WINDOW: Int = 8

    /** The sliding window of [MAX_CREATED_PER_WINDOW], in milliseconds (15 minutes). */
    const val CREATION_WINDOW_MS: Long = 15L * 60 * 1000

    /** The most settled requests the journal keeps per agent; older ones are pruned first. */
    const val MAX_SETTLED_PER_AGENT: Int = 16

    /** The most records a journal holds; a write that would exceed it is refused. */
    const val MAX_JOURNAL_RECORDS: Int = 512

    /** The default lifetime of a pending request, in milliseconds (15 minutes). */
    const val DEFAULT_TTL_MS: Long = 15L * 60 * 1000

    /** How long a settled record is kept after it settles, in milliseconds (24 hours). */
    const val SETTLED_RETENTION_MS: Long = 24L * 60 * 60 * 1000
}
