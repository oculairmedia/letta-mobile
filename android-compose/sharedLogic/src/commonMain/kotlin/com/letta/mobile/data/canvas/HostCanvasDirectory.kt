package com.letta.mobile.data.canvas

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * A canvas as the host knows it: which relay topic holds its ops, what it is called, and who may
 * read and write it. The ops themselves stay in the relay's log, the one truth for the scene; this
 * is only what the log does not say (letta-mobile-aknkw.2).
 */
@Serializable
data class HostCanvasEntry(
    val canvasId: String,
    val topic: String,
    val title: String,
    val conversationId: String? = null,
    val acl: CanvasAcl,
)

/**
 * The host's record of the canvases its agents use, keyed by canvas id. Entries are only ever
 * added, never replaced: the first binding of a canvas decides who owns it, so a later caller
 * cannot rewrite an ACL by creating the same canvas again.
 */
interface HostCanvasDirectory {
    suspend fun get(canvasId: String): HostCanvasEntry?

    suspend fun forConversation(conversationId: String): HostCanvasEntry?

    /** Stores [entry] unless its canvas (or its conversation's canvas) is known; returns the stored one. */
    suspend fun putIfAbsent(entry: HostCanvasEntry): HostCanvasEntry

    suspend fun all(): List<HostCanvasEntry>
}

/** The directory for tests and hosts that need no durability. */
class InMemoryHostCanvasDirectory : HostCanvasDirectory {
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, HostCanvasEntry>()

    override suspend fun get(canvasId: String): HostCanvasEntry? = mutex.withLock { entries[canvasId] }

    override suspend fun forConversation(conversationId: String): HostCanvasEntry? =
        mutex.withLock { entries.values.firstOrNull { it.conversationId == conversationId } }

    override suspend fun putIfAbsent(entry: HostCanvasEntry): HostCanvasEntry = mutex.withLock {
        existing(entries.values, entry) ?: entry.also { entries[it.canvasId] = it }
    }

    override suspend fun all(): List<HostCanvasEntry> = mutex.withLock { entries.values.toList() }
}

/** The entry already standing for [candidate]'s canvas or conversation, if any. */
internal fun existing(entries: Collection<HostCanvasEntry>, candidate: HostCanvasEntry): HostCanvasEntry? =
    entries.firstOrNull {
        it.canvasId == candidate.canvasId ||
            (candidate.conversationId != null && it.conversationId == candidate.conversationId)
    }
