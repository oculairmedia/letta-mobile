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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared transaction and replacement boundary for trusted host-owned sidecars. */
internal class DurableHostFile(
    file: Path,
    lockFile: Path?,
    private val directorySync: (Path) -> Unit,
) {
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

    fun write(text: String) {
        directorySync(path.parent)
        val temp = Files.createTempFile(path.parent, ".host-state-", ".tmp")
        try {
            FileChannel.open(temp, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(text.encodeToByteArray())
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
            directorySync(path.parent)
        } finally {
            Files.deleteIfExists(temp)
        }
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
