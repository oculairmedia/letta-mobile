package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasId
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Desktop file-backed implementation of [CanvasDocumentStore].
 *
 * Persists each [CanvasDocument] as an atomic JSON file under [rootDirectory].
 *
 * Writes are serialised per directory, not per instance: the desktop app and the App Server
 * tool registry each hold their own store over the same directory, and a second desktop
 * process may too. An in-process lock keyed by the directory covers the first case and an
 * OS file lock on `.store.lock` covers the second, so a revision check and the write it
 * guards cannot be interleaved with another writer's replace of the same file.
 */
class DesktopCanvasDocumentStore(
    private val rootDirectory: Path = defaultRootDirectory(),
) : CanvasDocumentStore {

    private val normalizedRoot: Path = rootDirectory.toAbsolutePath().normalize()
    private val mutex: Mutex = directoryLocks.computeIfAbsent(normalizedRoot) { Mutex() }
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    init {
        Files.createDirectories(rootDirectory)
    }

    override suspend fun get(id: CanvasId): CanvasDocument? = withContext(Dispatchers.IO) {
        mutex.withLock { readDocumentLocked(id) }
    }

    override suspend fun getForConversation(conversationId: String): CanvasDocument? = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadAllDocumentsInternal().firstOrNull { it.conversationId == conversationId }
        }
    }

    override suspend fun upsert(doc: CanvasDocument): Unit = withContext(Dispatchers.IO) {
        withWriteLock { writeDocumentLocked(doc) }
    }

    override suspend fun upsertIfRevision(doc: CanvasDocument, expectedRevision: Long): Boolean =
        withContext(Dispatchers.IO) {
            withWriteLock {
                val current = readDocumentLocked(doc.id) ?: return@withWriteLock false
                if (current.revision != expectedRevision) return@withWriteLock false
                writeDocumentLocked(doc)
                true
            }
        }

    override suspend fun createForConversationIfAbsent(doc: CanvasDocument): CanvasDocument =
        withContext(Dispatchers.IO) {
            val conversationId = requireNotNull(doc.conversationId) { "createForConversationIfAbsent needs a conversationId" }
            withWriteLock {
                loadAllDocumentsInternal().firstOrNull { it.conversationId == conversationId }
                    ?: doc.also { writeDocumentLocked(it) }
            }
        }

    /**
     * Runs [block] holding both the per-directory coroutine lock and an exclusive OS lock on the
     * directory's lock file. Callers are already on [Dispatchers.IO]; `FileChannel.lock` blocks
     * the thread until a competing process releases it.
     */
    private suspend inline fun <T> withWriteLock(crossinline block: () -> T): T = mutex.withLock {
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

    private fun readDocumentLocked(id: CanvasId): CanvasDocument? {
        val file = docFile(id)
        if (!Files.exists(file) || !Files.isRegularFile(file)) return null
        return try {
            json.decodeFromString(CanvasDocument.serializer(), Files.readString(file))
        } catch (unreadable: Exception) {
            // An unreadable or malformed document reads as absent, not as a crash.
            null
        }
    }

    private fun writeDocumentLocked(doc: CanvasDocument) {
        Files.createDirectories(rootDirectory)
        val file = docFile(doc.id)
        val tempFile = Files.createTempFile(rootDirectory, "canvas_", ".tmp")
        try {
            val content = json.encodeToString(CanvasDocument.serializer(), doc)
            Files.writeString(tempFile, content)
            try {
                Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    override suspend fun listForAgent(agentId: String): List<CanvasDocument> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadAllDocumentsInternal().filter { it.agentId == agentId }
        }
    }

    override suspend fun listAll(): List<CanvasDocument> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadAllDocumentsInternal().sortedByDescending { it.updatedAtEpochMs }
        }
    }

    private fun loadAllDocumentsInternal(): List<CanvasDocument> {
        if (!Files.isDirectory(rootDirectory)) return emptyList()
        val list = mutableListOf<CanvasDocument>()
        Files.newDirectoryStream(rootDirectory, "*.json").use { stream ->
            for (file in stream) {
                try {
                    val content = Files.readString(file)
                    list.add(json.decodeFromString(CanvasDocument.serializer(), content))
                } catch (_: Exception) {
                    // Ignore corrupted or unreadable files
                }
            }
        }
        return list
    }

    private fun docFile(id: CanvasId): Path {
        val safeName = id.value.sha256PathComponent()
        return rootDirectory.resolve("$safeName.json")
    }

    companion object {
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
        private const val LOCK_FILE = ".store.lock"

        /**
         * One coroutine lock per canvas directory, shared by every store instance in the
         * process. A lock table is process-scoped by nature: it models the directory, which is
         * itself shared, not any store's state, and a second instance holding its own mutex
         * would defeat the revision check.
         */
        private val directoryLocks = ConcurrentHashMap<Path, Mutex>()

        fun defaultRootDirectory(): Path {
            val userHome = System.getProperty("user.home") ?: "."
            return Paths.get(userHome, ".letta", "canvas")
        }

        private fun String.sha256PathComponent(): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(encodeToByteArray())
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX_DIGITS[value ushr 4])
                    append(HEX_DIGITS[value and 0x0f])
                }
            }
        }
    }
}
