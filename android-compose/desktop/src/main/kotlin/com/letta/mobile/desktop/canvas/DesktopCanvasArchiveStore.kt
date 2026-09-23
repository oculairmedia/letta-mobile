package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasArchiveStore
import com.letta.mobile.data.canvas.CanvasId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The desktop's archived canvases: one id per line in a small file beside the canvases
 * themselves ([DesktopCanvasDocumentStore.defaultRootDirectory]). Not a `.json` file, so the
 * document store's listing never mistakes it for a canvas. Written to a temporary file and moved
 * into place, so a crash mid-write leaves the previous list rather than half of one.
 */
internal class DesktopCanvasArchiveStore(
    private val rootDirectory: Path = DesktopCanvasDocumentStore.defaultRootDirectory(),
) : CanvasArchiveStore {
    private val mutex = Mutex()
    private val file: Path get() = rootDirectory.resolve(FILE_NAME)

    override suspend fun archivedIds(): Set<CanvasId> = mutex.withLock { withContext(Dispatchers.IO) { read() } }

    override suspend fun setArchived(id: CanvasId, archived: Boolean) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val ids = read().toMutableSet()
                val changed = if (archived) ids.add(id) else ids.remove(id)
                if (changed) write(ids)
            }
        }
    }

    private fun read(): Set<CanvasId> {
        if (!Files.exists(file)) return emptySet()
        return runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapTo(LinkedHashSet()) { CanvasId(it) }
    }

    private fun write(ids: Set<CanvasId>) {
        Files.createDirectories(rootDirectory)
        val temp = Files.createTempFile(rootDirectory, "archived_", ".tmp")
        try {
            Files.write(temp, ids.map { it.value })
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private companion object {
        const val FILE_NAME = "archived-canvases.txt"
    }
}
