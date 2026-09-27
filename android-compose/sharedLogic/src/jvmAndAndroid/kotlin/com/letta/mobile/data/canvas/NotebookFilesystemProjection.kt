package com.letta.mobile.data.canvas

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import org.automerge.repo.DocumentId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Explicit polling avoids watcher feedback: call reconcile when external edits should be imported. */
enum class NotebookProjectionResult { UNCHANGED, EXPORTED, IMPORTED, CONFLICT }

internal class NotebookFilesystemProjection(private val root: Path) {
    @Serializable
    private data class Baseline(val id: String, val markdown: String, val yaml: String)

    private data class Snapshot(val markdown: String, val yaml: String) {
        fun baseline(id: String) = Baseline(id, digest(markdown), digest(yaml))
    }

    fun project(store: NotebookLocalStore, id: DocumentId): NotebookProjectionResult = sync(store, id, false)
    fun reconcile(store: NotebookLocalStore, id: DocumentId): NotebookProjectionResult = sync(store, id, true)

    private fun sync(store: NotebookLocalStore, id: DocumentId, import: Boolean): NotebookProjectionResult {
        val document = requireNotNull(store.read(id)) { "Unknown notebook document: $id" }
        val key = digest(document.id.value)
        checkDirectory(root)
        val folder = root.resolve(key)
        checkDirectory(folder)
        val markdownFile = folder.resolve("note.md")
        val yamlFile = folder.resolve("board.yaml")
        val baselineFile = folder.resolve(".baseline.json")
        val current = Snapshot(document.markdown, yaml(document.title, document.sceneJson))
        val diskMarkdown = safeRead(markdownFile)
        val diskYaml = safeRead(yamlFile)
        val disk = if (diskMarkdown != null && diskYaml != null) Snapshot(diskMarkdown, diskYaml) else null
        val previous = safeRead(baselineFile)?.let { Json.decodeFromString<Baseline>(it) }
        require(previous == null || previous.id == document.id.value) { "Projection ID mismatch" }
        if (previous == null) {
            if (disk != null && disk != current) return NotebookProjectionResult.CONFLICT
            if (disk == null && (diskMarkdown != null || diskYaml != null)) return NotebookProjectionResult.CONFLICT
            publish(markdownFile, yamlFile, baselineFile, current, document.id.value)
            return NotebookProjectionResult.EXPORTED
        }
        val localChanged = current.baseline(document.id.value) != previous
        val diskChanged = disk?.baseline(document.id.value) != previous
        if (!localChanged && !diskChanged) return NotebookProjectionResult.UNCHANGED
        if (disk == null) return NotebookProjectionResult.CONFLICT // Never silently restore a deleted external file.
        if (localChanged && diskChanged && current != disk) return NotebookProjectionResult.CONFLICT
        if (diskChanged && !localChanged) {
            if (!import) return NotebookProjectionResult.CONFLICT
            val (title, board) = parseYaml(disk.yaml)
            store.replaceProjection(id, title, disk.markdown, board)
            atomicWrite(baselineFile, Json.encodeToString(Baseline.serializer(), disk.baseline(document.id.value)))
            return NotebookProjectionResult.IMPORTED
        }
        publish(markdownFile, yamlFile, baselineFile, current, document.id.value)
        return NotebookProjectionResult.EXPORTED
    }

    private fun publish(md: Path, yaml: Path, baseline: Path, snapshot: Snapshot, id: String) {
        atomicWrite(md, snapshot.markdown)
        atomicWrite(yaml, snapshot.yaml)
        atomicWrite(baseline, Json.encodeToString(Baseline.serializer(), snapshot.baseline(id)))
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

    private fun yaml(title: String, board: String): String = buildString {
        append("schema: notebook-board/1\nboardVersion: 1\ntitle: ")
        append(Json.encodeToString(kotlinx.serialization.serializer<String>(), title))
        append("\nboard: |-\n")
        board.split('\n').forEach { append("  ").append(it).append('\n') }
    }

    private fun parseYaml(value: String): Pair<String, String> {
        val lines = value.split('\n')
        require(lines.size >= 5 && lines[0] == "schema: notebook-board/1" && lines[1] == "boardVersion: 1" && lines[2].startsWith("title: ") && lines[3] == "board: |-") { "Unsupported board YAML schema" }
        val title = Json.decodeFromString<String>(lines[2].removePrefix("title: "))
        val body = lines.drop(4).dropLastWhile { it.isEmpty() }
        require(body.isNotEmpty() && body.all { it.startsWith("  ") }) { "Invalid board YAML block" }
        val board = body.joinToString("\n") { it.removePrefix("  ") }
        require(Json.parseToJsonElement(board).jsonObject["schema"]?.jsonPrimitive?.content == "notebook-board/1") {
            "Unsupported notebook board schema"
        }
        return title to board
    }

    private companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
