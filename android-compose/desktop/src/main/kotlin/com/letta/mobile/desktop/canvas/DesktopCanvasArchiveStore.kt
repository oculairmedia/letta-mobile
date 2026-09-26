package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasArchiveStore
import com.letta.mobile.data.canvas.CanvasId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * The desktop's archived canvases: one id per line in a small file beside the canvases
 * themselves ([DesktopCanvasDocumentStore.defaultRootDirectory]). Not a `.json` file, so the
 * document store's listing never mistakes it for a canvas. Written to a temporary file and moved
 * into place, so a crash mid-write leaves the previous list rather than half of one.
 *
 * An archive or restore is a read-modify-write of that one file, and more than one store (or app
 * process) may share the directory: a per-directory lock shared by every instance in the process,
 * plus an OS lock on a lock file, keeps one update from overwriting another. A file that cannot be
 * read fails the update rather than being taken as empty, which would drop every archived id.
 */
internal class DesktopCanvasArchiveStore(
    private val rootDirectory: Path = DesktopCanvasDocumentStore.defaultRootDirectory(),
) : CanvasArchiveStore {
    private val mutex: Mutex = directoryLocks.computeIfAbsent(rootDirectory.toAbsolutePath().normalize()) { Mutex() }
    private val file: Path get() = rootDirectory.resolve(FILE_NAME)

    override suspend fun archivedIds(): Set<CanvasId> = mutex.withLock { withContext(Dispatchers.IO) { read() } }

    override suspend fun setArchived(id: CanvasId, archived: Boolean) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                withFileLock {
                    val ids = read().toMutableSet()
                    val changed = if (archived) ids.add(id) else ids.remove(id)
                    if (changed) write(ids)
                }
            }
        }
    }

    private inline fun withFileLock(block: () -> Unit) {
        Files.createDirectories(rootDirectory)
        FileChannel.open(rootDirectory.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        }
    }

    private fun read(): Set<CanvasId> {
        if (!Files.exists(file)) return emptySet()
        return Files.readAllLines(file)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapTo(LinkedHashSet()) { CanvasId(it) }
    }

    private fun write(ids: Set<CanvasId>) {
        val temp = Files.createTempFile(rootDirectory, "archived_", ".tmp")
        try {
            Files.write(temp, ids.map { it.value })
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                // Not every file system replaces atomically; a plain replace still beats no update.
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private companion object {
        const val FILE_NAME = "archived-canvases.txt"
        const val LOCK_FILE = ".archive.lock"

        /** One lock per canvas directory, shared by every store instance in the process. */
        val directoryLocks = ConcurrentHashMap<Path, Mutex>()
    }
}
