package com.letta.mobile.runtime

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Stable nonblank backend scope for runtime events and storage.
 */
@JvmInline
@Serializable
value class BackendId(/** Validated primitive representation of this value. */ val value: String) {
    init {
        require(value.isNotBlank()) { "BackendId cannot be blank." }
    }

    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value
}

/**
 * Nonblank runtime identity within a backend, distinct from a connection generation.
 */
@JvmInline
@Serializable
value class RuntimeId(/** Validated primitive representation of this value. */ val value: String) {
    init {
        require(value.isNotBlank()) { "RuntimeId cannot be blank." }
    }

    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value
}

/**
 * Nonblank conversation ID for runtime contracts. Distinct from the data-layer ID; convert explicitly at edges.
 */
@JvmInline
@Serializable
value class ConversationId(/** Validated primitive representation of this value. */ val value: String) {
    init {
        require(value.isNotBlank()) { "ConversationId cannot be blank." }
    }

    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value
}

/**
 * Nonblank runtime run token. Scope by backend/runtime rather than assuming global uniqueness across restarts.
 */
@JvmInline
@Serializable
value class RunId(/** Validated primitive representation of this value. */ val value: String) {
    init {
        require(value.isNotBlank()) { "RunId cannot be blank." }
    }

    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value
}

/**
 * Nonblank identity used by the projector to recognize replayed events.
 */
@JvmInline
@Serializable
value class RuntimeEventId(/** Validated primitive representation of this value. */ val value: String) {
    init {
        require(value.isNotBlank()) { "RuntimeEventId cannot be blank." }
    }

    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value
}

/**
 * Nonnegative exclusive outbox resume position. Compare only within the same outbox.
 */
@JvmInline
@Serializable
value class RuntimeEventOffset(/** Validated primitive representation of this value. */ val value: Long) : Comparable<RuntimeEventOffset> {
    init {
        require(value >= 0L) { "RuntimeEventOffset cannot be negative." }
    }

    /** Compares the underlying numeric values within the same logical scope. */
    override fun compareTo(other: RuntimeEventOffset): Int = value.compareTo(other.value)
    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value.toString()
}

/**
 * Nonnegative Unix-epoch milliseconds used as metadata, not an ordering cursor.
 */
@JvmInline
@Serializable
value class EpochMillis(/** Validated primitive representation of this value. */ val value: Long) {
    init {
        require(value >= 0L) { "EpochMillis cannot be negative." }
    }

    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value.toString()
}
