package com.letta.mobile.data.controller.node.iroh

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * One lock per host state file, for the whole process. A host store serializes its read, revision check and
 * replace under a lock; when several stores are built over the same file (every on-device dial builds its own
 * routes), a lock per store would let two of them accept a proposal against the same revision and silently
 * drop one. Stores take their lock from here, keyed by the file's absolute normalized path, so every store of
 * a file shares it. Locks are never released: a host has a handful of state files.
 */
internal object HostStateFileLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** The lock every store of [file] must hold across its read-check-write. */
    fun forFile(file: Path): Mutex = locks.computeIfAbsent(file.toAbsolutePath().normalize().toString()) { Mutex() }
}
