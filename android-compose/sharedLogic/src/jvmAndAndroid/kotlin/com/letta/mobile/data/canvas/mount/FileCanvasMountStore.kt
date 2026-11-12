package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.hoststate.DurableHostFile
import com.letta.mobile.data.hoststate.HostDirectoryDurability
import com.letta.mobile.data.hoststate.StrictHostJson
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.storage.sha256Hex
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Trusted host-administration storage, NOT an authenticated remote authority. The existing protected
 * product-state directory must already exist and be administrator-owned. All writers must use this
 * store. Each default or agent/conversation binding owns one hashed file beneath `canvas-mounts/`.
 * Operations read/validate only that key, never enumerate the population or rewrite another key.
 *
 * The first hash byte selects one of 256 shared runtime owners and OS locks in `canvas-mounts/locks/`.
 * A stripe serializes read/check/write across instances and processes; colliding keys serialize safely
 * while replacing distinct files. Absent reads create neither directories, files nor runtime owners.
 * Existing reads require the writer-created stripe lock and never create it. Records are limited to
 * 8 KiB and validate version, host and exact key, including misplaced/hash-collision files.
 * Values are semantic [CanvasId]s, never notebook generation IDs. Existing winners are directory-synced
 * without replacing already-forced contents. No document, provenance or client preferences change.
 * It trusts every value it is given and authorizes nothing, so it is internal: callers outside this
 * package reach it only through [AuthorizedCanvasMounts], which authorizes scope, proposal and winner.
 */
internal class FileCanvasMountStore internal constructor(
    productStateDirectory: Path,
    private val hostId: HostId,
    private val directorySync: (Path) -> Unit,
) : CanvasMountStorage {
    /**
     * On Windows contents are forced and atomically replaced, but directory entries are unconfirmed;
     * a newest binding lost after a power cut is simply set again.
     */
    constructor(productStateDirectory: Path, hostId: HostId) :
        this(productStateDirectory, hostId, HostDirectoryDurability::syncMount)

    private val parent = productStateDirectory.toAbsolutePath().normalize()
    private val directory = parent.resolve("canvas-mounts")
    private val lockDirectory = directory.resolve("locks")

    init { require(hostId.value.isNotBlank()) { "Empty mount host" } }

    /** Reads only this explicit binding. Null means absent; corrupt/unreadable state throws. */
    override suspend fun readBinding(agentId: AgentId, conversationId: ConversationId): CanvasId? =
        readKey(agentId, conversationId)

    /** Reads only this agent's default, without granting canvas access or creating a binding. */
    override suspend fun readDefault(agentId: AgentId): CanvasId? =
        readKey(agentId, null)

    /** Atomically inserts only when absent, returning the durable winner without rewriting it. */
    override suspend fun bindIfAbsent(agentId: AgentId, conversationId: ConversationId, proposed: CanvasId): CanvasId =
        transaction(agentId, conversationId, proposed) { file ->
            val winner = read(file, agentId, conversationId)
            // Reconfirm the root's own directory entry on every mutation/retry, including creation
            // whose previous parent-directory sync may have failed. No unrelated data file is read.
            syncDirectories()
            when (winner) {
                null -> {
                    write(file, Record(VERSION, hostId, agentId, conversationId, proposed.value))
                    proposed
                }
                else -> {
                    // syncDirectories confirmed the entry; contents were already forced before rename.
                    CanvasId(winner.canvas)
                }
            }
        }

    /** Durably replaces only this conversation's binding, leaving all other key files untouched. */
    override suspend fun replaceBinding(agentId: AgentId, conversationId: ConversationId, canvasId: CanvasId) =
        transaction(agentId, conversationId, canvasId) { file ->
            read(file, agentId, conversationId)
            syncDirectories()
            write(file, Record(VERSION, hostId, agentId, conversationId, canvasId.value))
        }

    /** Durably sets only this default. Existing conversation bindings are neither read nor rewritten. */
    override suspend fun setDefault(agentId: AgentId, canvasId: CanvasId) = transaction(agentId, null, canvasId) { file ->
        read(file, agentId, null)
        syncDirectories()
        write(file, Record(VERSION, hostId, agentId, null, canvasId.value))
    }

    private suspend fun readKey(agent: AgentId, conversation: ConversationId?): CanvasId? = withContext(Dispatchers.IO) {
        validate(agent, conversation)
        val attributes = attributesOrNull(directory) ?: return@withContext null
        require(attributes.isDirectory) { "Mount directory is not a directory" }
        val file = fileFor(agent, conversation)
        if (attributesOrNull(file.path) == null) return@withContext null
        // A record exists, so a writer made its lock entries. Where the host cannot confirm directory
        // entries (Windows) a power cut can leave the record without them: put them back instead of
        // failing every later read of a valid binding. A read of an absent key still creates nothing.
        ensureDirectory(lockDirectory)
        file.locked { read(file, agent, conversation)?.canvas?.let(::CanvasId) }
    }

    private suspend fun <T> transaction(
        agent: AgentId,
        conversation: ConversationId?,
        canvas: CanvasId? = null,
        action: suspend (DurableHostFile) -> T,
    ): T = withContext(Dispatchers.IO) {
        validate(agent, conversation, canvas)
        ensureDirectory(directory)
        ensureDirectory(lockDirectory)
        val file = fileFor(agent, conversation)
        file.locked { action(file) }
    }

    private fun fileFor(agent: AgentId, conversation: ConversationId?): DurableHostFile {
        val identity = JsonArray(listOf(JsonPrimitive(agent.value), conversation?.let { JsonPrimitive(it.value) } ?: JsonNull))
        val hash = sha256Hex(identity.toString().encodeToByteArray())
        val kind = if (conversation == null) DEFAULT_RECORD else BINDING_RECORD
        val lock = lockDirectory.resolve("${hash.take(STRIPE_HEX_CHARS)}.lock")
        return DurableHostFile(directory.resolve("$kind-$hash.json"), lock, directorySync)
    }

    private fun ensureDirectory(path: Path) {
        try {
            Files.createDirectory(path)
        } catch (_: FileAlreadyExistsException) {
            // Existing directories are validated without following a substituted symlink.
        }
        require(attributesOrNull(path)?.isDirectory == true) { "Mount directory is not a directory" }
    }

    private fun syncDirectories() {
        directorySync(parent)
        directorySync(directory)
        directorySync(lockDirectory)
    }

    private fun read(file: DurableHostFile, agent: AgentId, conversation: ConversationId?): Record? {
        val attributes = attributesOrNull(file.path) ?: return null
        require(attributes.isRegularFile) { "Mount record is not a regular file" }
        require(attributes.size() <= MAX_RECORD_BYTES) { "Mount record is too large" }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file.path))).toString()
        StrictHostJson.requireUniqueKeys(text)
        requireStrictFormat(text)
        val record = Json.decodeFromString<Record>(text)
        require(record.version == VERSION) { "Unsupported mount record version" }
        require(record.host == hostId) { "Mount record belongs to a different host" }
        require(record.agent == agent && record.conversation == conversation) { "Misplaced mount record key" }
        validate(record.agent, record.conversation, CanvasId(record.canvas))
        return record
    }

    private fun requireStrictFormat(text: String) {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Mount record must be an object")
        require(root.keys == setOf("version", "host", "agent", "conversation", "canvas")) { "Invalid mount record fields" }
        val version = root["version"] as? JsonPrimitive ?: error("Invalid mount record version")
        require(!version.isString && version.content == VERSION.toString()) { "Unsupported mount record version" }
        listOf("host", "agent", "canvas").forEach { key ->
            require((root[key] as? JsonPrimitive)?.isString == true) { "Invalid mount key" }
        }
        val conversation = root["conversation"]
        require(conversation == JsonNull || (conversation as? JsonPrimitive)?.isString == true) { "Invalid mount conversation" }
    }

    private fun write(file: DurableHostFile, record: Record) {
        val text = Json.encodeToString(Record.serializer(), record)
        require(text.encodeToByteArray().size <= MAX_RECORD_BYTES) { "Mount record is too large" }
        file.write(text)
    }

    private fun validate(agent: AgentId, conversation: ConversationId? = null, canvas: CanvasId? = null) {
        require(agent.value.isNotBlank()) { "Empty mount agent" }
        require(conversation == null || conversation.value.isNotBlank()) { "Empty mount conversation" }
        require(canvas == null || canvas.value.isNotBlank()) { "Empty mount canvas" }
    }

    // The sidecar stores the semantic string, not CanvasId's document-metadata object serializer.
    @Serializable
    private data class Record(
        val version: Int,
        val host: HostId,
        val agent: AgentId,
        val conversation: ConversationId?,
        val canvas: String,
    )

    private companion object {
        const val VERSION = 1
        const val MAX_RECORD_BYTES = 8 * 1024
        const val STRIPE_HEX_CHARS = 2

        /** Record file name prefixes: an agent's default mount, and one agent/thread binding. */
        const val DEFAULT_RECORD = "default"
        const val BINDING_RECORD = "binding"
    }
}

private fun attributesOrNull(path: Path): BasicFileAttributes? = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
} catch (_: NoSuchFileException) {
    null
}
