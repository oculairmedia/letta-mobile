package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.hoststate.DurableHostFile
import com.letta.mobile.data.hoststate.StrictHostJson
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.HostId
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Trusted host-administration storage, NOT an authenticated remote authority. The runtime owns one
 * instance under its existing protected product-state directory; independent instances/processes
 * share its transaction lock. The directory must already exist and be administrator-owned.
 *
 * Keys are host + agent + conversation (including agent-scoped default-conversation aliases).
 * Values are semantic [CanvasId]s, never notebook generation IDs. No document or client preferences
 * are changed. There is deliberately no RPC registration, principal inference or client fallback.
 * S2 must bind verified principals and authorize scope, proposal AND stored winner before exposure.
 */
class FileCanvasMountStore internal constructor(
    productStateDirectory: Path,
    private val hostId: HostId,
    directorySync: (Path) -> Unit,
) {
    /** Directory syncing is required except on Windows, whose directory channels are unsupported. */
    constructor(productStateDirectory: Path, hostId: HostId) : this(productStateDirectory, hostId, { directory ->
        if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)) {
            FileChannel.open(directory).use { it.force(true) }
        }
    })

    private val file = DurableHostFile(productStateDirectory.resolve(FILE_NAME), directorySync)

    init { require(hostId.value.isNotBlank()) { "Empty mount host" } }

    /** Reads an explicit binding. Null means absent; corrupt/unreadable state throws. */
    suspend fun readBinding(agentId: AgentId, conversationId: ConversationId): CanvasId? = file.locked {
        validate(agentId, conversationId)
        read().bindings.singleOrNull { it.agent == agentId && it.conversation == conversationId }?.canvas
    }

    /** Reads an agent default; it neither grants canvas access nor creates a conversation binding. */
    suspend fun readDefault(agentId: AgentId): CanvasId? = file.locked {
        validate(agentId)
        read().defaults.singleOrNull { it.agent == agentId }?.canvas
    }

    /** Atomically inserts only if absent and returns the durable stored winner. */
    suspend fun bindIfAbsent(agentId: AgentId, conversationId: ConversationId, proposed: CanvasId): CanvasId = file.locked {
        validate(agentId, conversationId, proposed)
        val current = read()
        val winner = current.bindings.singleOrNull { it.agent == agentId && it.conversation == conversationId }?.canvas
        if (winner != null) {
            // Reconfirm durability after an earlier ambiguous replacement before acknowledging it.
            write(current)
            winner
        } else {
            write(current.copy(bindings = current.bindings + Binding(agentId, conversationId, proposed)))
            proposed
        }
    }

    /** Explicitly replaces only this conversation's binding, with durable acknowledgement. */
    suspend fun replaceBinding(agentId: AgentId, conversationId: ConversationId, canvasId: CanvasId) = file.locked {
        validate(agentId, conversationId, canvasId)
        val current = read()
        val others = current.bindings.filterNot { it.agent == agentId && it.conversation == conversationId }
        write(current.copy(bindings = others + Binding(agentId, conversationId, canvasId)))
    }

    /** Explicitly sets only the agent default. Existing conversation bindings remain untouched. */
    suspend fun setDefault(agentId: AgentId, canvasId: CanvasId) = file.locked {
        validate(agentId, canvas = canvasId)
        val current = read()
        write(current.copy(defaults = current.defaults.filterNot { it.agent == agentId } + Default(agentId, canvasId)))
    }

    private fun read(): Table {
        val path = file.path
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return Table(VERSION, hostId, emptyList(), emptyList())
        }
        require(attributes.isRegularFile) { "Mount store is not a regular file" }
        require(attributes.size() <= MAX_BYTES) { "Mount store is too large" }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString()
        StrictHostJson.requireUniqueKeys(text)
        requireStrictFormat(text)
        val table = Json.decodeFromString<Table>(text)
        require(table.version == VERSION) { "Unsupported mount store version" }
        require(table.host == hostId) { "Mount store belongs to a different host" }
        table.defaults.forEach { validate(it.agent, canvas = it.canvas) }
        table.bindings.forEach { validate(it.agent, it.conversation, it.canvas) }
        require(table.defaults.map { it.agent }.distinct().size == table.defaults.size) { "Duplicate mount default" }
        require(table.bindings.map { it.agent to it.conversation }.distinct().size == table.bindings.size) { "Duplicate mount binding" }
        return table
    }

    private fun requireStrictFormat(text: String) {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: error("Mount store must be an object")
        require(root.keys == setOf("version", "host", "defaults", "bindings")) { "Invalid mount store fields" }
        val version = root["version"] as? JsonPrimitive ?: error("Invalid mount store version")
        require(!version.isString && version.content == VERSION.toString()) { "Unsupported mount store version" }
        requireStringFields(root, setOf("host"))
        requireEntries(root["defaults"] as? JsonArray, setOf("agent", "canvas"))
        requireEntries(root["bindings"] as? JsonArray, setOf("agent", "conversation", "canvas"))
    }

    private fun requireEntries(entries: JsonArray?, keys: Set<String>) {
        requireNotNull(entries) { "Invalid mount list" }.forEach { value ->
            val entry = value as? JsonObject ?: error("Invalid mount entry")
            require(entry.keys == keys) { "Invalid mount fields" }
            requireStringFields(entry, keys)
        }
    }

    private fun requireStringFields(entry: JsonObject, keys: Set<String>) {
        keys.forEach { key -> require((entry[key] as? JsonPrimitive)?.isString == true) { "Invalid mount key" } }
    }

    private fun write(table: Table) {
        val text = Json.encodeToString(Table.serializer(), table)
        require(text.encodeToByteArray().size <= MAX_BYTES) { "Mount store is too large" }
        file.write(text)
    }

    private fun validate(agent: AgentId, conversation: ConversationId? = null, canvas: CanvasId? = null) {
        require(agent.value.isNotBlank()) { "Empty mount agent" }
        require(conversation == null || conversation.value.isNotBlank()) { "Empty mount conversation" }
        require(canvas == null || canvas.value.isNotBlank()) { "Empty mount canvas" }
    }

    @Serializable
    private data class Table(val version: Int, val host: HostId, val defaults: List<Default>, val bindings: List<Binding>)

    @Serializable
    private data class Default(val agent: AgentId, val canvas: CanvasId)

    @Serializable
    private data class Binding(val agent: AgentId, val conversation: ConversationId, val canvas: CanvasId)

    private companion object {
        const val VERSION = 1
        const val MAX_BYTES = 1024 * 1024
        const val FILE_NAME = "canvas-mounts.json"
    }
}
