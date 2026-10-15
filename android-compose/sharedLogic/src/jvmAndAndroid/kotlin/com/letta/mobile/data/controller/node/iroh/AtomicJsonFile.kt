package com.letta.mobile.data.controller.node.iroh

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Crash-safe replacement of a small JSON state file (paired peers, pending
 * invites): write a temp sibling, fsync it, atomically rename over the target,
 * then best-effort fsync the directory so the rename itself is durable.
 */
internal object AtomicJsonFile {
    fun write(path: Path, text: String) {
        path.parent?.let(Files::createDirectories)
        val temp = path.resolveSibling(".${path.fileName}.tmp-${java.util.UUID.randomUUID()}")
        // fsync contents+metadata BEFORE the rename so a power loss can never
        // expose a partially written or zero-byte file through the durable name.
        FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        // Directory fsync is unsupported on some platforms (Windows throws when
        // opening a directory channel); the file fsync + rename still hold.
        path.parent?.let { parent ->
            runCatching { FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) } }
        }
    }

    fun readOrNull(path: Path): String? {
        if (!Files.exists(path)) return null
        return String(Files.readAllBytes(path), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    /**
     * Run [block] holding an exclusive OS file lock on a `.lock` sibling of
     * [path], so two processes sharing the state file (the wrapper server and
     * the host `pair` CLI) never interleave read-modify-write cycles. A JVM-wide
     * monitor per path is taken first: FileChannel locks are held per process,
     * and a second in-process lock attempt would throw instead of waiting.
     */
    fun <T> withProcessLock(path: Path, block: () -> T): T {
        val absolute = path.toAbsolutePath().normalize()
        val monitor = inProcessLocks.computeIfAbsent(absolute.toString()) { PathMonitor() }
        return synchronized(monitor) {
            absolute.parent?.let(Files::createDirectories)
            val lockPath = absolute.resolveSibling("${absolute.fileName}.lock")
            FileChannel.open(lockPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE).use { channel ->
                channel.lockWaitingForOtherHolders().use { block() }
            }
        }
    }

    /**
     * [FileChannel.lock] blocks while another PROCESS holds the lock, but throws
     * [OverlappingFileLockException] when another channel in THIS JVM holds it
     * outside our monitor (a second classloader, a tool, a test standing in for
     * another process). Treat both the same way: wait, never clobber.
     */
    private fun FileChannel.lockWaitingForOtherHolders(): FileLock {
        while (true) {
            try {
                return lock()
            } catch (_: OverlappingFileLockException) {
                Thread.sleep(IN_JVM_HOLDER_RETRY_MS)
            }
        }
    }

    private const val IN_JVM_HOLDER_RETRY_MS = 10L

    /** The in-process half of [withProcessLock]: one monitor per absolute state-file path. */
    private class PathMonitor

    private val inProcessLocks = java.util.concurrent.ConcurrentHashMap<String, PathMonitor>()
}
