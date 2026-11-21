package com.letta.mobile.data.controller.node.iroh.feed

import java.security.MessageDigest
import kotlin.jvm.JvmInline

/** A turn of a journal: the client message id of its prompt, else a host-minted `turn-<uuid>`. */
@JvmInline
internal value class JournalTurn(val value: String) {
    override fun toString(): String = value
}

/**
 * The journal's name for a turn: [raw] (a client message id) when it is short, else a stable hash of it, so a hostile or
 * careless id cannot make every entry of a page carry kilobytes. The same raw id always gives the same turn, and a raw id
 * that starts with the reserved hash prefix is hashed as well, so no raw id can equal a generated one.
 */
internal fun journalTurn(raw: String): JournalTurn {
    // The hash prefix is reserved: a raw id that already looks like a generated one is hashed too, so the two never collide.
    if (raw.length <= MAX_TURN_ID_CHARS && !raw.startsWith(HASHED_TURN_PREFIX)) return JournalTurn(raw)
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return JournalTurn(HASHED_TURN_PREFIX + digest.joinToString("") { "%02x".format(it) })
}

private const val MAX_TURN_ID_CHARS = 128
private const val HASHED_TURN_PREFIX = "sha256:"

/** A position in one conversation's journal; only grows, and a superseded snapshot leaves a hole. */
@JvmInline
internal value class FeedSeq(val value: Long) : Comparable<FeedSeq> {
    override fun compareTo(other: FeedSeq): Int = value.compareTo(other.value)

    fun next(): FeedSeq = FeedSeq(value + 1)
}

/** The position before the first entry. */
internal val NO_SEQ = FeedSeq(0)

/** One host start: greater than every epoch an earlier start issued. */
@JvmInline
internal value class FeedEpoch(val value: Long)

/** Which logical text message an entry is a cumulative snapshot of (its type and its logical id). */
@JvmInline
internal value class TextKey(val value: String)

/** A size in bytes. */
@JvmInline
internal value class ByteCount(val value: Long) : Comparable<ByteCount> {
    override fun compareTo(other: ByteCount): Int = value.compareTo(other.value)

    operator fun plus(other: ByteCount) = ByteCount(value + other.value)

    operator fun minus(other: ByteCount) = ByteCount((value - other.value).coerceAtLeast(0))
}

/** No bytes. */
internal val NO_BYTES = ByteCount(0)
