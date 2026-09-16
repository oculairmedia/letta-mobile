package com.letta.mobile.desktop.canvas

import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasId
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Desktop file-backed implementation of [CanvasDocumentStore].
 *
 * Persists each [CanvasDocument] as an atomic JSON file under [rootDirectory].
 */
class DesktopCanvasDocumentStore(
    private val rootDirectory: Path = defaultRootDirectory(),
) : CanvasDocumentStore {

    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    init {
        Files.createDirectories(rootDirectory)
    }

    override suspend fun get(id: CanvasId): CanvasDocument? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = docFile(id)
            if (!Files.exists(file) || !Files.isRegularFile(file)) return@withLock null
            try {
                val content = Files.readString(file)
                json.decodeFromString(CanvasDocument.serializer(), content)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
        }
    }

    override suspend fun getForConversation(conversationId: String): CanvasDocument? = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadAllDocumentsInternal().firstOrNull { it.conversationId == conversationId }
        }
    }

    override suspend fun upsert(doc: CanvasDocument): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
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
    }

    override suspend fun listForAgent(agentId: String): List<CanvasDocument> = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadAllDocumentsInternal().filter { it.agentId == agentId }
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
