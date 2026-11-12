package com.letta.mobile.data.hoststate

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.OpenOption
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared transaction and replacement boundary for trusted host-owned sidecars.
 *
 * A replacement has one commit point: the atomic move of the forced temp file over [path].
 * - Before the move (parent sync, temp creation, write, force, [DurableHostFileHooks.beforeMove],
 *   cancellation check): nothing changed, the temp file is removed, and the result is
 *   [DurableReplacement.NotApplied]. Cancellation observed here propagates.
 * - The move itself failing: [DurableReplacement.NotApplied] while the temp file still exists (the
 *   rename did not happen), otherwise [DurableReplacement.Unknown].
 * - After the move ([DurableHostFileHooks.afterMove], the directory sync): no cancellation check runs and
 *   every failure, cancellation included, is [DurableReplacement.Unknown]; success is
 *   [DurableReplacement.Applied]. Once the move happened the call always completes with a value.
 *
 * Where [directorySync] cannot confirm directory entries (Windows, see [HostDirectoryDurability.syncMount])
 * the sync is a no-op and a successful move is reported as [DurableReplacement.Applied]: contents are
 * forced and atomically replaced, but the entry itself is unconfirmed. That is an accepted limitation.
 */
internal class DurableHostFile(
    file: Path,
    lockFile: Path?,
    private val directorySync: (Path) -> Unit,
    private val hooks: DurableHostFileHooks,
) {
    constructor(file: Path, lockFile: Path?, directorySync: (Path) -> Unit) :
        this(file, lockFile, directorySync, DurableHostFileHooks.NONE)

    constructor(file: Path, directorySync: (Path) -> Unit) : this(file, null, directorySync)

    val path: Path = file.toAbsolutePath().normalize()
    private val lockPath = (lockFile ?: path.resolveSibling("${path.fileName}.lock")).toAbsolutePath().normalize()

    suspend fun <T> locked(action: suspend () -> T): T = locked(createLock = true, action)

    suspend fun <T> locked(createLock: Boolean, action: suspend () -> T): T = withContext(Dispatchers.IO) {
        val canonical = requireNotNull(lockPath.parent).toRealPath().resolve(lockPath.fileName)
        locks.computeIfAbsent(canonical) { Mutex() }.withLock {
            val options = when (createLock) {
                true -> setOf<OpenOption>(CREATE, WRITE, NOFOLLOW_LINKS)
                false -> setOf<OpenOption>(WRITE, NOFOLLOW_LINKS)
            }
            FileChannel.open(canonical, options).use { channel ->
                val lock = acquire(channel)
                try { action() } finally { lock.release() }
            }
        }
    }

    /** Test-only observation of runtime owners beneath this instance's existing parent directory. */
    internal fun lockOwnerCount(): Int {
        val parent = requireNotNull(path.parent).toRealPath()
        return locks.keys.count { it.startsWith(parent) }
    }

    /** Replaces [path] with [text], throwing the failure of any outcome other than [DurableReplacement.Applied]. */
    fun write(text: String) {
        when (val outcome = replace(text) {}) {
            DurableReplacement.Applied -> Unit
            is DurableReplacement.NotApplied -> throw outcome.cause
            is DurableReplacement.Unknown -> throw outcome.cause
        }
    }

    /**
     * Replaces [path] with [text] and reports which side of the commit point a failure fell on. Cancellation
     * of the calling coroutine is checked immediately before the move and propagates from there; after the
     * move the call completes regardless.
     */
    suspend fun replace(text: String): DurableReplacement {
        val context = currentCoroutineContext()
        return replace(text) { context.ensureActive() }
    }

    private fun replace(text: String, checkpoint: () -> Unit): DurableReplacement {
        val temp = runCatching { stage(text) }.getOrElse { return notApplied(it) }
        return try {
            commit(temp, checkpoint)
        } finally {
            discard(temp)
        }
    }

    private fun stage(text: String): Path {
        directorySync(path.parent)
        val temp = Files.createTempFile(path.parent, ".host-state-", ".tmp")
        runCatching {
            FileChannel.open(temp, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(text.encodeToByteArray())
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }.onFailure { failure ->
            discard(temp)
            throw failure
        }
        return temp
    }

    private fun commit(temp: Path, checkpoint: () -> Unit): DurableReplacement {
        runCatching { hooks.beforeMove() }.exceptionOrNull()?.let { return notApplied(it) }
        checkpoint()
        val moved = runCatching { Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING) }
        moved.exceptionOrNull()?.let { failure ->
            return if (Files.exists(temp, NOFOLLOW_LINKS)) notApplied(failure) else unknown(failure)
        }
        val confirmed = runCatching {
            hooks.afterMove()
            directorySync(path.parent)
        }
        return confirmed.exceptionOrNull()?.let(::unknown) ?: DurableReplacement.Applied
    }

    private fun notApplied(failure: Throwable): DurableReplacement {
        if (failure is CancellationException) throw failure
        return DurableReplacement.NotApplied(rethrowFatal(failure))
    }

    private fun unknown(failure: Throwable): DurableReplacement = DurableReplacement.Unknown(rethrowFatal(failure))

    private fun rethrowFatal(failure: Throwable): Throwable {
        if (failure is VirtualMachineError) throw failure
        return failure
    }

    /** Best-effort temp cleanup; a leftover hidden temp file never changes the record or the outcome. */
    private fun discard(temp: Path) {
        runCatching { Files.deleteIfExists(temp) }
    }

    private suspend fun acquire(channel: FileChannel): java.nio.channels.FileLock {
        while (true) {
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock != null) return lock
            delay(LOCK_RETRY_MILLIS)
        }
    }

    private companion object {
        const val LOCK_RETRY_MILLIS = 10L
        val locks = ConcurrentHashMap<Path, Mutex>()
    }
}

/**
 * Where a [DurableHostFile.replace] ended relative to its commit point, the atomic move. Each failure keeps
 * its cause for internal classification only; callers must not expose it.
 */
internal sealed interface DurableReplacement {
    /** The move happened and the directory entry was synced (or cannot be confirmed on this host). */
    data object Applied : DurableReplacement

    /** Failed before the move, or the move did not happen: the previous contents are unchanged. */
    class NotApplied(
        /** The failure, for classification only. */
        val cause: Throwable,
    ) : DurableReplacement

    /** Failed at or after the move: the new contents may or may not be in place. Read back. */
    class Unknown(
        /** The failure, for classification only. */
        val cause: Throwable,
    ) : DurableReplacement
}

/** Test seams around the commit point of a [DurableHostFile] replacement; production uses [NONE]. */
internal class DurableHostFileHooks(
    /** Runs after the temp file is forced and before the cancellation check and move. */
    val beforeMove: () -> Unit = {},
    /** Runs immediately after a successful move, before the directory sync. */
    val afterMove: () -> Unit = {},
) {
    /** Shared no-op hooks. */
    companion object {
        /** No-op hooks used outside tests. */
        val NONE = DurableHostFileHooks()
    }
}
