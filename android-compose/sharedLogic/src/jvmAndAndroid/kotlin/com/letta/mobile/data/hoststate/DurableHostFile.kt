package com.letta.mobile.data.hoststate

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
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
internal class DurableHostFile(file: Path, private val directorySync: (Path) -> Unit) {
    val path: Path = file.toAbsolutePath().normalize()

    suspend fun <T> locked(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        val canonical = requireNotNull(path.parent).toRealPath().resolve(path.fileName)
        locks.computeIfAbsent(canonical) { Mutex() }.withLock {
            val lockPath = canonical.resolveSibling("${canonical.fileName}.lock")
            FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val lock = acquire(channel)
                try { action() } finally { lock.release() }
            }
        }
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
