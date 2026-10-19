package com.letta.mobile.runtime

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Absolute virtual-memory path, not an OS filesystem permission.
 * Requires a leading slash and rejects double-slash empty segments; it does not normalize
 * dot segments or establish containment beneath a host directory.
 */
@JvmInline
@Serializable
value class MemFsPath(
    /** Virtual path beginning with / and containing no //; dot segments are retained. */
    val value: String
) {
    init {
        require(value.startsWith("/")) { "MemFsPath must be absolute." }
        require("//" !in value) { "MemFsPath cannot contain empty segments." }
    }

    /** Returns the virtual path unchanged, including any dot segments. */
    override fun toString(): String = value
}

/**
 * Nonnegative ordered revision in the runtime memory model, not a Git commit SHA.
 */
@JvmInline
@Serializable
value class MemFsRevision(
    /** Nonnegative memory revision number; no backend or file identity is encoded. */
    val value: Long
) : Comparable<MemFsRevision> {
    init {
        require(value >= 0L) { "MemFsRevision cannot be negative." }
    }

    /** Orders revision numbers without checking backend or memory-store ownership. */
    override fun compareTo(other: MemFsRevision): Int = value.compareTo(other.value)
    /** Returns the memory revision as a base-10 integer. */
    override fun toString(): String = value.toString()
}

/**
 * Nonblank runtime-memory commit identifier. Its format is backend-owned.
 */
@JvmInline
@Serializable
value class MemFsCommitId(
    /** Nonblank memory-commit key; no particular hash format or commit existence is checked. */
    val value: String
) {
    init {
        require(value.isNotBlank()) { "MemFsCommitId cannot be blank." }
    }

    /** Returns the memory-commit key in its backend-defined format. */
    override fun toString(): String = value
}

/** Optional presentation metadata accompanying a virtual-memory file. */
@Serializable
data class MemFsMetadata(
    /** Media type, or null when unknown. */
    val mimeType: String? = null,
    /** Optional human-readable explanation of the file. */
    val description: String? = null,
    /** Labels used to organize or discover the file. */
    val tags: Set<String> = emptySet(),
)

/**
 * Text and metadata at a virtual path/revision; this value performs no I/O.
 */
@Serializable
data class MemFsFile(
    /** Absolute virtual-memory location of this file. */
    val path: MemFsPath,
    /** Revision associated with this memory file or commit. */
    val revision: MemFsRevision,
    /** Text content at the stated revision. */
    val content: String,
    /** Presentation metadata stored with the text. */
    val metadata: MemFsMetadata = MemFsMetadata(),
)

/**
 * Change marker for one path at a revision. Content is separate in [MemFsFile].
 */
@Serializable
data class MemFsCommit(
    /** Backend-owned identity of the memory commit. */
    val id: MemFsCommitId,
    /** Revision associated with this memory file or commit. */
    val revision: MemFsRevision,
    /** Virtual path affected by the commit. */
    val path: MemFsPath,
    /** Change applied to the virtual path. */
    val operation: MemFsOperation,
    /** Creation time in Unix-epoch milliseconds; not an ordering cursor. */
    val createdAt: EpochMillis,
)

/** Kind of change recorded by a memory commit. */
@Serializable
enum class MemFsOperation {
    /** Creation or replacement of file content. */
    Write,
    /** Removal of the virtual file. */
    Delete,
}
