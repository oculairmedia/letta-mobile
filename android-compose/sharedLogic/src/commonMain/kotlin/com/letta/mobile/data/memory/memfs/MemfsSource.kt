package com.letta.mobile.data.memory.memfs

import kotlinx.coroutines.flow.Flow

/**
 * The port the MemFS browser reads and writes through (letta-mobile-bzvro.24).
 * [AppServerMemfsSource] serves it from an App Server connection; tests fake it.
 *
 * Every call throws [MemfsException] when the server refuses or cannot answer it, with a
 * message fit to show; a stalled request surfaces as a timeout rather than a partial result.
 */
interface MemfsSource {
    /** Every memory file of [agentId], or a listing with `enabled = false` when MemFS is off. */
    suspend fun list(agentId: String): MemfsListing

    /** The file exactly as stored, frontmatter included: what an editor must round-trip. */
    suspend fun read(agentId: String, path: String): String

    /** Writes and commits [content] at [path]; returns the commit sha when one was made. */
    suspend fun write(agentId: String, path: String, content: String): String?

    /** Commits touching [path] (every commit when null), newest first. */
    suspend fun history(agentId: String, path: String?): List<MemfsCommit>

    /** The patch of commit [sha]. */
    suspend fun commitDiff(agentId: String, sha: String): String

    /** [path] as it was at [ref]. */
    suspend fun fileAtRef(agentId: String, path: String, ref: String): String

    suspend fun enable(agentId: String)

    /** `memory_updated` pushes from the server; never completes. */
    val updates: Flow<MemfsUpdate>
}
