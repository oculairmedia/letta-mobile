package com.letta.mobile.runtime

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Nonblank identity of an imported/exported agent file, distinct from its virtual path. */
@JvmInline
@Serializable
value class AgentFileId(/** Validated primitive representation of this value. */ val value: String) {
    init {
        require(value.isNotBlank()) { "AgentFileId cannot be blank." }
    }

    /** Returns the underlying value without a wrapper label. */
    override fun toString(): String = value
}

/**
 * Imported/exported agent-file descriptor. [path] is a virtual [MemFsPath], not a host path.
 * This stores metadata rather than bytes; absent [mimeType]/[sizeBytes] means unknown.
 */
@Serializable
data class AgentFile(
    /** Stable identity of this agent file. */
    val id: AgentFileId,
    /** Virtual-memory location of the file. */
    val path: MemFsPath,
    /** Human-readable file name for display. */
    val displayName: String,
    /** Media type, or null when unknown. */
    val mimeType: String? = null,
    /** Reported byte length, or null when unknown. */
    val sizeBytes: Long? = null,
    /** Backend-provided descriptive attributes. */
    val metadata: Map<String, String> = emptyMap(),
)
