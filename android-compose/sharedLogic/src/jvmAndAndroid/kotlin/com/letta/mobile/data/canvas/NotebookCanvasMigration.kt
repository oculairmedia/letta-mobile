package com.letta.mobile.data.canvas

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.automerge.repo.DocumentId

/** A durable, explicit claim of an existing notebook ID for a legacy canvas. */
data class NotebookCanvasMapping(val canvasId: CanvasId, val target: DocumentId, val completed: Boolean)

/**
 * Store this alongside the notebook repository. All instances using the same directory coordinate
 * through a file lock, including instances in other processes. Never remove the lock file: removing
 * it while another process holds a lock would allow a second, independent lock inode.
 */
class NotebookCanvasMappingStore(private val directory: Path) {
    @Serializable
    private data class Entry(val canvasId: String, val targetHex: String, val fingerprint: String, val completed: Boolean)

    @Serializable
    private data class State(val entries: List<Entry> = emptyList())

    private val mappingFile = directory.resolve("notebook-canvas-mappings.json")
    private val lockFile = directory.resolve("notebook-canvas-mappings.lock")

    /** Returns null for an unclaimed canvas; corrupt state is an error, not an empty mapping. */
    fun lookup(canvasId: CanvasId): NotebookCanvasMapping? = locked { entries ->
        entries.firstOrNull { it.canvasId == canvasId.value }?.let { entry ->
            NotebookCanvasMapping(canvasId, decode(entry.targetHex), entry.completed)
        }
    }

    /** Hold the interprocess lock through import and completion, not just the claim write. */
    internal fun migrate(canvas: CanvasDocument, target: DocumentId, importer: () -> NotebookCanvasImportResult): NotebookCanvasImportResult = locked { entries ->
        val key = target.hex()
        val fingerprint = fingerprint(canvas)
        val entry = entries.firstOrNull { it.canvasId == canvas.id.value }
        if (entry != null && (entry.targetHex != key || entry.fingerprint != fingerprint) ||
            entry == null && entries.any { it.targetHex == key }
        ) return@locked NotebookCanvasImportResult.CONFLICT
        if (entry == null) write(entries + Entry(canvas.id.value, key, fingerprint, false))
        val result = importer()
        if (result != NotebookCanvasImportResult.CONFLICT && entry?.completed != true) {
            val current = read()
            write(current.map { if (it.canvasId == canvas.id.value) it.copy(completed = true) else it })
        }
        result
    }

    private fun <T> locked(action: (List<Entry>) -> T): T {
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Notebook mapping directory does not exist: $directory" }
        require(!Files.isSymbolicLink(lockFile)) { "Mapping lock must not be a symlink" }
        FileChannel.open(lockFile, CREATE, WRITE).use { channel ->
            // FileLock throws rather than waits for a lock already held by this JVM.
            while (true) {
                try {
                    channel.lock().use { return action(read()) }
                } catch (_: OverlappingFileLockException) {
                    Thread.sleep(10)
                }
            }
        }
    }

    private fun read(): List<Entry> {
        if (!Files.exists(mappingFile, NOFOLLOW_LINKS)) return emptyList()
        require(Files.isRegularFile(mappingFile, NOFOLLOW_LINKS)) { "Not a regular mapping file: $mappingFile" }
        val entries = Json.decodeFromString<State>(Files.readString(mappingFile, UTF_8)).entries
        require(entries.all { it.canvasId.isNotBlank() && it.fingerprint.matches(Regex("[0-9a-f]{64}")) &&
            runCatching { decode(it.targetHex) }.isSuccess } &&
            entries.map { it.canvasId }.distinct().size == entries.size &&
            entries.map { it.targetHex }.distinct().size == entries.size) { "Invalid notebook canvas mappings" }
        return entries
    }

    private fun write(entries: List<Entry>) {
        val temp = Files.createTempFile(directory, ".notebook-canvas-mappings-", ".tmp")
        try {
            FileChannel.open(temp, WRITE).use { channel ->
                val data = ByteBuffer.wrap(Json.encodeToString(State.serializer(), State(entries)).toByteArray(UTF_8))
                while (data.hasRemaining()) channel.write(data)
                channel.force(true)
            }
            Files.move(temp, mappingFile, ATOMIC_MOVE, REPLACE_EXISTING)
            // Some platforms do not support forcing a directory; the atomic rename still holds.
            runCatching { FileChannel.open(directory, READ).use { it.force(true) } }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun fingerprint(canvas: CanvasDocument): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(canvas.id.value, canvas.title, canvas.sceneJson).forEach { value ->
            val bytes = value.toByteArray(UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun DocumentId.hex(): String = getBytes().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decode(hex: String): DocumentId {
        require(hex.matches(Regex("[0-9a-f]{32}"))) { "Invalid notebook document ID" }
        return DocumentId.fromBytes(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }
}

/** The caller creates the target and supplies the legacy snapshot; this never deletes the source. */
class NotebookCanvasMigration(
    private val store: NotebookLocalStore,
    private val mappings: NotebookCanvasMappingStore,
) {
    fun migrate(canvas: CanvasDocument, selectedTarget: DocumentId): NotebookCanvasImportResult {
        require(canvas.id.value.isNotBlank()) { "Canvas ID must not be blank" }
        // Do not record a dangling claim for a target that has not been created by the caller.
        requireNotNull(store.open(selectedTarget)) { "Target notebook does not exist: $selectedTarget" }
        return mappings.migrate(canvas, selectedTarget) {
            NotebookCanvasBridge(store).import(canvas, selectedTarget)
        }
    }
}
