package com.letta.mobile.data.canvas

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.repo.DocumentId

/** Explicit polling avoids watcher feedback: call reconcile when external edits should be imported. */
enum class NotebookProjectionResult { UNCHANGED, EXPORTED, IMPORTED, CONFLICT, ERROR }

internal class NotebookFilesystemProjection(private val root: Path) {
    @Serializable
    private data class Baseline(val id: String, val markdown: String, val yaml: String)

    private data class Snapshot(val markdown: String, val yaml: String) {
        fun baseline(id: String) = Baseline(id, digest(markdown), digest(yaml))
    }

    private class ProjectionFiles(folder: Path) {
        val markdown: Path = folder.resolve("note.md")
        val yaml: Path = folder.resolve("board.yaml")
        val baseline: Path = folder.resolve(".baseline.json")
    }

    fun hasBaseline(id: DocumentId): Boolean {
        val folder = folder(id.getBytes().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') })
        if (!Files.exists(folder, NOFOLLOW_LINKS)) return false
        require(Files.isDirectory(folder, NOFOLLOW_LINKS)) { "Not a safe projection directory: $folder" }
        return safeRead(folder.resolve(".baseline.json")) != null
    }

    fun project(store: NotebookLocalStore, id: DocumentId): NotebookProjectionResult =
        SyncSession(store, id, allowImport = false).execute()

    private fun folder(id: String): Path = root.resolve(digest(id))

    fun reconcile(store: NotebookLocalStore, id: DocumentId): NotebookProjectionResult =
        SyncSession(store, id, allowImport = true).execute()

    private inner class SyncSession(
        private val store: NotebookLocalStore,
        private val id: DocumentId,
        private val allowImport: Boolean,
    ) {
        private val document = requireNotNull(store.read(id)) { "Unknown notebook document: $id" }
        private val docId = document.id.value
        private val files = ProjectionFiles(folder(docId).also {
            checkDirectory(root)
            checkDirectory(it)
        })
        private val current = Snapshot(markdown(document.title, document.markdown), yaml(document.sceneJson))
        private val diskMarkdown = safeRead(files.markdown)
        private val diskYaml = safeRead(files.yaml)
        private val disk = if (diskMarkdown != null && diskYaml != null) Snapshot(diskMarkdown, diskYaml) else null
        private val previous = safeRead(files.baseline)?.let { Json.decodeFromString<Baseline>(it) }

        fun execute(): NotebookProjectionResult {
            require(previous == null || previous.id == docId) { "Projection ID mismatch" }
            return if (previous == null) {
                syncInitial()
            } else {
                syncIncremental(previous)
            }
        }

        private fun syncInitial(): NotebookProjectionResult {
            if (isPartialDisk()) return NotebookProjectionResult.CONFLICT
            if (disk != null && disk != current) return NotebookProjectionResult.CONFLICT
            return exportCurrent(disk)
        }

        private fun isPartialDisk(): Boolean =
            (diskMarkdown == null) != (diskYaml == null)

        private fun syncIncremental(previous: Baseline): NotebookProjectionResult {
            val localChanged = current.baseline(docId) != previous
            val diskChanged = disk?.baseline(docId) != previous
            if (!localChanged && !diskChanged) return NotebookProjectionResult.UNCHANGED
            if (disk == null) return NotebookProjectionResult.CONFLICT // Never silently restore a deleted external file.
            if (hasConflict(localChanged, diskChanged)) return NotebookProjectionResult.CONFLICT
            if (!localChanged) {
                return applyExternalImport(disk)
            }
            return exportCurrent(disk)
        }

        private fun hasConflict(localChanged: Boolean, diskChanged: Boolean): Boolean {
            if (!localChanged || !diskChanged) return false
            return current != disk
        }

        private fun exportCurrent(expected: Snapshot?): NotebookProjectionResult =
            if (publish(files, current, docId, expected)) {
                NotebookProjectionResult.EXPORTED
            } else {
                NotebookProjectionResult.CONFLICT
            }

        private fun applyExternalImport(disk: Snapshot): NotebookProjectionResult {
            if (!allowImport) return NotebookProjectionResult.CONFLICT
            val (title, body) = parseMarkdown(disk.markdown)
            val board = parseYaml(disk.yaml)
            // An external save during parsing must not be acknowledged by the baseline.
            if (diskChangedDuringImport(disk)) return NotebookProjectionResult.CONFLICT
            if (!store.replaceProjection(id, document, NotebookLocalStore.NotebookContent(title, body, board))) {
                return NotebookProjectionResult.CONFLICT
            }
            if (diskChangedDuringImport(disk)) return NotebookProjectionResult.CONFLICT
            atomicWrite(files.baseline, Json.encodeToString(Baseline.serializer(), disk.baseline(docId)))
            return NotebookProjectionResult.IMPORTED
        }

        private fun diskChangedDuringImport(expected: Snapshot): Boolean =
            safeRead(files.markdown) != expected.markdown || safeRead(files.yaml) != expected.yaml
    }

    private fun publish(files: ProjectionFiles, snapshot: Snapshot, id: String, expected: Snapshot?): Boolean {
        if (safeRead(files.markdown) != expected?.markdown || safeRead(files.yaml) != expected?.yaml) return false
        atomicWrite(files.markdown, snapshot.markdown)
        atomicWrite(files.yaml, snapshot.yaml)
        atomicWrite(files.baseline, Json.encodeToString(Baseline.serializer(), snapshot.baseline(id)))
        return true
    }

    private fun checkDirectory(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Not a safe projection directory: $path" }
        } else {
            val parent = path.parent
            if (parent != null && !Files.isDirectory(parent, NOFOLLOW_LINKS)) checkDirectory(parent)
            Files.createDirectory(path)
        }
    }

    private fun safeRead(path: Path): String? {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Not a regular projection file: $path" }
        return Files.readString(path, UTF_8)
    }

    private fun atomicWrite(path: Path, value: String) {
        safeRead(path) // Reject symlinks and non-regular destinations before replacement.
        val temp = Files.createTempFile(path.parent, ".notebook-", ".tmp")
        try {
            Files.writeString(temp, value, UTF_8)
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun markdown(title: String, body: String): String =
        "---\nschema: notebook/1\ntitle: ${Json.encodeToString(kotlinx.serialization.serializer<String>(), title)}\n---\n$body"

    private fun parseMarkdown(value: String): Pair<String, String> {
        val match = Regex("\\A---\\r?\\nschema: notebook/1\\r?\\ntitle: ([^\\r\\n]+)\\r?\\n---\\r?\\n").find(value)
        requireNotNull(match) { "Invalid notebook Markdown front matter" }
        return Json.decodeFromString<String>(match.groupValues[1]) to value.substring(match.range.last + 1)
    }

    private fun yaml(board: String): String = buildString {
        append("schema: notebook-board/1\nboardVersion: 1\nboard: |-\n")
        board.split('\n').forEach { append("  ").append(it).append('\n') }
    }

    private fun parseYaml(value: String): String {
        val lines = value.split('\n')
        require(lines.size >= 4 && lines[0] == "schema: notebook-board/1" && lines[1] == "boardVersion: 1" && lines[2] == "board: |-") { "Unsupported board YAML schema" }
        val body = lines.drop(3).dropLastWhile { it.isEmpty() }
        require(body.isNotEmpty() && body.all { it.startsWith("  ") }) { "Invalid board YAML block" }
        val board = body.joinToString("\n") { it.removePrefix("  ") }
        require(Json.parseToJsonElement(board).jsonObject["schema"]?.jsonPrimitive?.content == "notebook-board/1") {
            "Unsupported notebook board schema"
        }
        return board
    }

    private companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
