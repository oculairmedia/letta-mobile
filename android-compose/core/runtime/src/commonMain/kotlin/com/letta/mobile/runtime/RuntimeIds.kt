package com.letta.mobile.runtime

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class BackendId(val value: String) {
    init {
        require(value.isNotBlank()) { "BackendId cannot be blank." }
    }

    override fun toString(): String = value
}

@JvmInline
@Serializable
value class RuntimeId(val value: String) {
    init {
        require(value.isNotBlank()) { "RuntimeId cannot be blank." }
    }

    override fun toString(): String = value
}

@JvmInline
@Serializable
value class ConversationId(val value: String) {
    init {
        require(value.isNotBlank()) { "ConversationId cannot be blank." }
    }

    override fun toString(): String = value
}

@JvmInline
@Serializable
value class RunId(val value: String) {
    init {
        require(value.isNotBlank()) { "RunId cannot be blank." }
    }

    override fun toString(): String = value
}

@JvmInline
@Serializable
value class RuntimeEventId(val value: String) {
    init {
        require(value.isNotBlank()) { "RuntimeEventId cannot be blank." }
    }

    override fun toString(): String = value
}

@JvmInline
@Serializable
value class RuntimeEventOffset(val value: Long) : Comparable<RuntimeEventOffset> {
    init {
        require(value >= 0L) { "RuntimeEventOffset cannot be negative." }
    }

    override fun compareTo(other: RuntimeEventOffset): Int = value.compareTo(other.value)
    override fun toString(): String = value.toString()
}

@JvmInline
@Serializable
value class EpochMillis(val value: Long) {
    init {
        require(value >= 0L) { "EpochMillis cannot be negative." }
    }

    override fun toString(): String = value.toString()
}
