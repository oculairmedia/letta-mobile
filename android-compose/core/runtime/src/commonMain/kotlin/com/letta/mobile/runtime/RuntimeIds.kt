package com.letta.mobile.runtime

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Stable nonblank backend scope for runtime events and storage.
 */
@JvmInline
@Serializable
value class BackendId(
    /** Nonblank backend key; whitespace is preserved and backend existence is not checked. */
    val value: String
) {
    init {
        require(value.isNotBlank()) { "BackendId cannot be blank." }
    }

    /** Returns the backend key exactly as stored. */
    override fun toString(): String = value
}

/**
 * Nonblank runtime identity within a backend, distinct from a connection generation.
 */
@JvmInline
@Serializable
value class RuntimeId(
    /** Nonblank runtime key; construction does not bind it to a particular backend. */
    val value: String
) {
    init {
        require(value.isNotBlank()) { "RuntimeId cannot be blank." }
    }

    /** Returns the runtime key without adding backend scope. */
    override fun toString(): String = value
}

/**
 * Nonblank conversation ID for runtime contracts. Distinct from the data-layer ID; convert explicitly at edges.
 */
@JvmInline
@Serializable
value class ConversationId(
    /** Nonblank conversation key; construction does not verify a server-side conversation. */
    val value: String
) {
    init {
        require(value.isNotBlank()) { "ConversationId cannot be blank." }
    }

    /** Returns the conversation key for explicit conversion at runtime boundaries. */
    override fun toString(): String = value
}

/**
 * Nonblank runtime run token. Scope by backend/runtime rather than assuming global uniqueness across restarts.
 */
@JvmInline
@Serializable
value class RunId(
    /** Nonblank run token; uniqueness and restart scoping are the caller's responsibility. */
    val value: String
) {
    init {
        require(value.isNotBlank()) { "RunId cannot be blank." }
    }

    /** Returns the run token without adding runtime or connection scope. */
    override fun toString(): String = value
}

/**
 * Nonblank identity used by the projector to recognize replayed events.
 */
@JvmInline
@Serializable
value class RuntimeEventId(
    /** Nonblank event token used for replay deduplication; uniqueness is not checked here. */
    val value: String
) {
    init {
        require(value.isNotBlank()) { "RuntimeEventId cannot be blank." }
    }

    /** Returns the event token retained by replay deduplication. */
    override fun toString(): String = value
}

/**
 * Nonnegative exclusive outbox resume position. Compare only within the same outbox.
 */
@JvmInline
@Serializable
value class RuntimeEventOffset(
    /** Nonnegative outbox cursor value; no outbox identity is encoded in this number. */
    val value: Long
) : Comparable<RuntimeEventOffset> {
    init {
        require(value >= 0L) { "RuntimeEventOffset cannot be negative." }
    }

    /** Orders offset numbers; callers must ensure both cursors belong to the same outbox. */
    override fun compareTo(other: RuntimeEventOffset): Int = value.compareTo(other.value)
    /** Returns the outbox offset as a base-10 integer. */
    override fun toString(): String = value.toString()
}

/**
 * Nonnegative Unix-epoch milliseconds used as metadata, not an ordering cursor.
 */
@JvmInline
@Serializable
value class EpochMillis(
    /** Nonnegative milliseconds since the Unix epoch; construction does not read a clock. */
    val value: Long
) {
    init {
        require(value >= 0L) { "EpochMillis cannot be negative." }
    }

    /** Returns the timestamp as a base-10 millisecond count. */
    override fun toString(): String = value.toString()
}
