package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.hoststate.DurableHostFile
import com.letta.mobile.data.hoststate.StrictHostJson
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.storage.sha256Hex
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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
 * The shared canonical-path runtime owner and OS lock serialize each key's read/check/write across
 * instances and processes. Independent keys cannot lose each other's changes: they replace distinct
 * files. Each record validates version, host and exact key, including a misplaced/hash-collision file.
 * Values are semantic [CanvasId]s, never notebook generation IDs. Existing winners are directory-synced
 * without replacing already-forced contents. No document, provenance or client preferences change.
 * No RPC, principal inference or fallback is supplied; S2 must authorize scope, proposal AND winner.
 */
class FileCanvasMountStore internal constructor(
    productStateDirectory: Path,
    private val hostId: HostId,
    private val directorySync: (Path) -> Unit,
) {
    /** Directory syncing is required except on Windows, whose directory channels are unsupported. */
    constructor(productStateDirectory: Path, hostId: HostId) : this(productStateDirectory, hostId, { directory ->
        if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
            FileChannel.open(directory).use { it.force(true) }
        }
    })

    private val parent = productStateDirectory.toAbsolutePath().normalize()
    private val directory = parent.resolve("canvas-mounts")

    init { require(hostId.value.isNotBlank()) { "Empty mount host" } }

    /** Reads only this explicit binding. Null means absent; corrupt/unreadable state throws. */
    suspend fun readBinding(agentId: AgentId, conversationId: ConversationId): CanvasId? =
        transaction(agentId, conversationId) { file -> read(file, agentId, conversationId)?.canvas?.let(::CanvasId) }

    /** Reads only this agent's default, without granting canvas access or creating a binding. */
    suspend fun readDefault(agentId: AgentId): CanvasId? =
        transaction(agentId, null) { file -> read(file, agentId, null)?.canvas?.let(::CanvasId) }

    /** Atomically inserts only when absent, returning the durable winner without rewriting it. */
    suspend fun bindIfAbsent(agentId: AgentId, conversationId: ConversationId, proposed: CanvasId): CanvasId =
        transaction(agentId, conversationId, proposed) { file ->
            val winner = read(file, agentId, conversationId)
            // Reconfirm the root's own directory entry on every mutation/retry, including creation
            // whose previous parent-directory sync may have failed. No unrelated data file is read.
            directorySync(parent)
            when (winner) {
                null -> {
                    write(file, Record(VERSION, hostId, agentId, conversationId, proposed.value))
                    proposed
                }
                else -> {
                    // Contents were forced before rename; only its directory entry may be unconfirmed.
                    directorySync(directory)
                    CanvasId(winner.canvas)
                }
            }
        }

    /** Durably replaces only this conversation's binding, leaving all other key files untouched. */
    suspend fun replaceBinding(agentId: AgentId, conversationId: ConversationId, canvasId: CanvasId) =
        transaction(agentId, conversationId, canvasId) { file ->
            read(file, agentId, conversationId)
            directorySync(parent)
            write(file, Record(VERSION, hostId, agentId, conversationId, canvasId.value))
        }

    /** Durably sets only this default. Existing conversation bindings are neither read nor rewritten. */
    suspend fun setDefault(agentId: AgentId, canvasId: CanvasId) = transaction(agentId, null, canvasId) { file ->
        read(file, agentId, null)
        directorySync(parent)
        write(file, Record(VERSION, hostId, agentId, null, canvasId.value))
    }

    private suspend fun <T> transaction(
        agent: AgentId,
        conversation: ConversationId?,
        canvas: CanvasId? = null,
        action: suspend (DurableHostFile) -> T,
    ): T = withContext(Dispatchers.IO) {
        validate(agent, conversation, canvas)
        ensureDirectory()
        val identity = JsonArray(listOf(JsonPrimitive(agent.value), conversation?.let { JsonPrimitive(it.value) } ?: JsonNull))
        val kind = if (conversation == null) "default" else "binding"
        val file = DurableHostFile(directory.resolve("$kind-${sha256Hex(identity.toString().encodeToByteArray())}.json"), directorySync)
        file.locked { action(file) }
    }

    private fun ensureDirectory() {
        try {
            Files.createDirectory(directory)
        } catch (_: FileAlreadyExistsException) {
            // Existing directories are validated without following a substituted symlink.
        }
        require(Files.readAttributes(directory, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory) {
            "Mount directory is not a directory"
        }
    }

    private fun read(file: DurableHostFile, agent: AgentId, conversation: ConversationId?): Record? {
        val attributes = try {
            Files.readAttributes(file.path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
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
        const val MAX_RECORD_BYTES = 1024 * 1024
    }
}
