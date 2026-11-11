package com.letta.mobile.data.canvas.authority

import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookPeerGrant
import com.letta.mobile.data.hoststate.DurableHostFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Host-administrated file store. The containing directory must be writable only by the trusted
 * host administrator. All writers must use [provision] and [revoke], including other processes.
 * Transactions hold the OS lock throughout [NotebookGrantStore.withGrant]'s action.
 * Administration requires directory synchronization. A failure after replacement leaves its
 * durability unconfirmed and must be reported, even when the new record is already visible.
 */
class FileNotebookGrantStore internal constructor(
    file: Path,
    private val directorySync: (Path) -> Unit,
) : NotebookGrantStore {
    /** Use the host filesystem's directory durability barrier for administration. */
    constructor(file: Path) : this(file, { directory -> FileChannel.open(directory).use { it.force(true) } })

    private val path = file.toAbsolutePath().normalize()

    override suspend fun <T> withGrant(
        principal: String,
        documentId: NotebookDocumentId,
        action: suspend (NotebookPeerGrant?) -> T,
    ): T = locked {
        validateKey(principal, documentId)
        val entry = read().grants.singleOrNull { it.principal == principal && it.documentId == documentId.value }
        action(entry?.grant())
    }

    /** Add or replace one pair. These capabilities do not authorize plugin or agent-tool use. */
    suspend fun provision(documentId: NotebookDocumentId, grant: NotebookPeerGrant) = locked {
        validateKey(grant.peerId, documentId)
        require(!grant.mayExecutePlugins && !grant.mayUseAgentTools) { "Unsupported notebook grant capability" }
        val current = read()
        val updated = current.grants.filterNot { it.principal == grant.peerId && it.documentId == documentId.value }
        write(current.copy(grants = updated + Entry(grant.peerId, documentId.value, grant.mayRead, grant.mayEdit, grant.mayReference)))
    }

    /** Revoke one pair; completion is serialized after any earlier protected operation. */
    suspend fun revoke(principal: String, documentId: NotebookDocumentId) = locked {
        validateKey(principal, documentId)
        val current = read()
        write(current.copy(grants = current.grants.filterNot { it.principal == principal && it.documentId == documentId.value }))
    }

    private val file = DurableHostFile(path) { confirmDirectorySync() }

    private suspend fun <T> locked(action: suspend () -> T): T = file.locked(action)

    private fun read(): FileData {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return FileData(VERSION, emptyList())
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Notebook grant store is not a regular file" }
        require(Files.size(path) <= MAX_BYTES) { "Notebook grant store is too large" }
        val data = try {
            val content = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString()
            requireUniqueKeys(content)
            requireStrictFormat(content)
            Json.decodeFromString<FileData>(content)
        } catch (expectedFailure: Exception) {
            throw IllegalStateException("Malformed notebook grant store", expectedFailure)
        }
        require(data.version == VERSION) { "Unsupported notebook grant store version" }
        data.grants.forEach { validateKey(it.principal, NotebookDocumentId(it.documentId)) }
        require(data.grants.map { it.principal to it.documentId }.toSet().size == data.grants.size) {
            "Duplicate notebook grant"
        }
        return data
    }

    private fun requireStrictFormat(text: String) {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Grant store must be an object")
        require(root.keys == setOf("version", "grants")) { "Invalid grant store fields" }
        val version = root["version"] as? JsonPrimitive ?: error("Invalid grant store version")
        require(!version.isString && version.content == VERSION.toString()) { "Invalid grant store version" }
        val entries = root["grants"] as? JsonArray ?: error("Invalid grant list")
        entries.forEach { value ->
            val entry = value as? JsonObject ?: error("Invalid grant entry")
            requireGrantEntry(entry)
        }
    }

    private fun requireGrantEntry(entry: JsonObject) {
        require(entry.keys == setOf("principal", "documentId", "mayRead", "mayEdit", "mayReference")) {
            "Invalid grant fields"
        }
        listOf("principal", "documentId").forEach { key ->
            require((entry[key] as? JsonPrimitive)?.isString == true) { "Invalid grant key" }
        }
        listOf("mayRead", "mayEdit", "mayReference").forEach { key ->
            val capability = entry[key] as? JsonPrimitive ?: error("Invalid grant capability")
            require(!capability.isString && capability.content in setOf("true", "false")) { "Invalid grant capability" }
        }
    }

    // The JSON tree decoder otherwise silently keeps only the last duplicate object field.
    private fun requireUniqueKeys(text: String) {
        val scopes = ArrayDeque<JsonScope>()
        var cursor = 0
        while (cursor < text.length) {
            when (text[cursor]) {
                '{' -> { require(scopes.size < MAX_JSON_DEPTH); scopes.addLast(JsonScope(mutableSetOf(), true)); cursor++ }
                '[' -> { require(scopes.size < MAX_JSON_DEPTH); scopes.addLast(JsonScope(null, false)); cursor++ }
                '}', ']' -> { scopes.removeLast(); cursor++ }
                ',' -> { expectNextKey(scopes.lastOrNull()); cursor++ }
                '"' -> cursor = readJsonString(text, cursor, scopes.lastOrNull())
                else -> cursor++
            }
        }
    }

    private fun expectNextKey(scope: JsonScope?) {
        if (scope?.keys != null) scope.expectKey = true
    }

    private fun readJsonString(text: String, start: Int, scope: JsonScope?): Int {
        val end = jsonStringEnd(text, start)
        if (scope?.expectKey == true) {
            val key = Json.decodeFromString<String>(text.substring(start, end))
            require(checkNotNull(scope.keys).add(key)) { "Duplicate grant field" }
            scope.expectKey = false
        }
        return end
    }

    private class JsonScope(val keys: MutableSet<String>?, var expectKey: Boolean)

    private fun write(data: FileData) {
        val encoded = Json.encodeToString(FileData.serializer(), data)
        require(encoded.encodeToByteArray().size <= MAX_BYTES) { "Notebook grant store is too large" }
        file.write(encoded)
    }

    private fun confirmDirectorySync() = try {
        directorySync(path.parent)
    } catch (unsupported: UnsupportedOperationException) {
        throw UnsupportedOperationException("Notebook grant administration requires directory synchronization", unsupported)
    } catch (failure: IOException) {
        throw IOException("Notebook grant directory synchronization failed; durability is unconfirmed", failure)
    }

    private fun validateKey(principal: String, documentId: NotebookDocumentId) {
        require(principal.isNotBlank() && documentId.value.isNotBlank()) { "Empty notebook grant key" }
    }

    @Serializable
    private data class FileData(val version: Int, val grants: List<Entry>)

    @Serializable
    private data class Entry(
        val principal: String,
        val documentId: String,
        val mayRead: Boolean,
        val mayEdit: Boolean,
        val mayReference: Boolean,
    ) {
        fun grant() = NotebookPeerGrant(principal, mayRead, mayEdit, mayReference = mayReference)
    }

    private companion object {
        const val VERSION = 1
        const val MAX_BYTES = 1024 * 1024
        const val MAX_JSON_DEPTH = 8

        private fun jsonStringEnd(text: String, start: Int): Int {
            var cursor = start + 1
            while (cursor < text.length) {
                if (text[cursor] == '\\') cursor += 2
                else if (text[cursor++] == '"') break
            }
            return cursor
        }
    }
}
