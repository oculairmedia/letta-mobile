package com.letta.mobile.data.shellstate

import com.letta.mobile.data.shellstate.ShellStateSnapshot

/**
 * Where a client keeps a disposable mirror of the last shell state document the host answered, so a cold start
 * with the host out of reach still shows the last known document. It is never authoritative and never a base for
 * an edit: [SharedShellState] shows it as not live, and replaces it with whatever the host answers first. The
 * platform supplies the storage (the client store's declared disposable cache); implementations never throw.
 */
interface ShellStateSnapshotCache {
    /** The mirrored document, or null when there is none or it cannot be read. */
    fun load(): ShellStateSnapshot?

    /** Mirrors [snapshot]. */
    fun save(snapshot: ShellStateSnapshot)

    /** Forgets the mirror. */
    fun clear()

    companion object {
        /** No mirror: nothing is restored and nothing is kept. */
        val None: ShellStateSnapshotCache = object : ShellStateSnapshotCache {
            override fun load(): ShellStateSnapshot? = null
            override fun save(snapshot: ShellStateSnapshot) = Unit
            override fun clear() = Unit
        }
    }
}
