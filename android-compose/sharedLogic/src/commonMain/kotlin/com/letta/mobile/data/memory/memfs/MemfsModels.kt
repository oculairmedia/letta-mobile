package com.letta.mobile.data.memory.memfs

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.diff.DiffLine

/** What a memory file holds: markdown the agent reads, or an image such as its avatar. */
enum class MemfsFileKind { Markdown, Image }

/**
 * One file in an agent's MemFS (letta-mobile-bzvro.24). [isSystem] files live under `system/`
 * and are compiled into the agent's context; the rest are external memory it reads on demand.
 * [body] is the markdown without its frontmatter, as the listing carries it (empty for images).
 */
@Immutable
data class MemfsFile(
    val path: String,
    val isSystem: Boolean,
    val description: String?,
    val sizeBytes: Long,
    val kind: MemfsFileKind,
    val body: String = "",
) {
    /** The last path segment. */
    val name: String get() = path.substringAfterLast('/')

    /** The folder the file sits in, or empty at the memory root. */
    val folder: String get() = path.substringBeforeLast('/', missingDelimiterValue = "")
}

/** An agent's memory files, or [enabled] false when MemFS is off for it. */
@Immutable
data class MemfsListing(
    val enabled: Boolean,
    val files: List<MemfsFile>,
)

/** One commit in the memory repository's history. [timestamp] is ISO-8601 as git prints it. */
@Immutable
data class MemfsCommit(
    val sha: String,
    val message: String,
    val timestamp: String,
    val author: String?,
) {
    val shortSha: String get() = sha.take(SHORT_SHA_LENGTH)

    private companion object {
        const val SHORT_SHA_LENGTH = 7
    }
}

/** One file's part of a commit's patch, ready to render. */
@Immutable
data class MemfsFileDiff(
    val path: String,
    val change: MemfsFileChange,
    val lines: List<DiffLine>,
    val added: Int,
    val removed: Int,
)

enum class MemfsFileChange { Added, Modified, Deleted, Binary }

/**
 * A `memory_updated` push: the paths that changed, or [everything] when the server reports `*`
 * (MemFS was just enabled, or a pull rewrote the tree).
 */
@Immutable
data class MemfsUpdate(
    val paths: Set<String>,
) {
    val everything: Boolean get() = WILDCARD in paths

    fun touches(path: String): Boolean = everything || path in paths

    companion object {
        const val WILDCARD = "*"
    }
}

/** A MemFS request the server refused or could not answer; [message] is safe to show. */
class MemfsException(message: String, cause: Throwable? = null) : Exception(message, cause)
