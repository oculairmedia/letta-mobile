package com.letta.mobile.data.controller.node.iroh

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Agent `metadata` kept by Meridian, because the App Server does not keep it.
 *
 * letta-code's local backend `updateAgent` copies only name, description, system, tags, model,
 * hidden and model_settings into the stored record; a PATCH carrying `metadata` still reports
 * success and the metadata is dropped. Clients store per-agent settings there (the mascot identity,
 * `letta_mobile.avatar_style`), so without this every such write silently lived only on the device
 * that made it.
 *
 * One JSON object per agent under [dir], named by the base64url agent id, written atomically.
 * Metadata follows PATCH semantics: a write replaces the agent's whole map. Only Meridian writes
 * this directory, so the files are read once and then served from memory.
 */
class AgentMetadataSidecar(private val dir: File) {
    private val cache = ConcurrentHashMap<String, JsonObject>()

    @Volatile private var loaded = false
    private val lock = ReentrantLock()

    fun read(agentId: String): JsonObject? {
        ensureLoaded()
        return cache[agentId]
    }

    fun write(agentId: String, metadata: JsonObject) {
        lock.withLock {
            ensureLoaded()
            dir.mkdirs()
            val target = fileFor(agentId)
            val temp = File(dir, "${target.name}.tmp")
            temp.writeText(Json.encodeToString(JsonObject.serializer(), metadata))
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            cache[agentId] = metadata
        }
    }

    fun delete(agentId: String) {
        lock.withLock {
            ensureLoaded()
            fileFor(agentId).delete()
            cache.remove(agentId)
        }
    }

    /** [agent] with the stored metadata in place of its own, when this agent has any stored. */
    fun overlay(agent: JsonElement?): JsonElement? {
        val record = agent as? JsonObject ?: return agent
        val id = (record["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return agent
        val metadata = read(id) ?: return agent
        return JsonObject(record + (METADATA_KEY to metadata))
    }

    fun overlayAll(agents: JsonArray): JsonArray = JsonArray(agents.map { overlay(it) ?: it })

    private fun ensureLoaded() {
        if (loaded) return
        lock.withLock {
            if (loaded) return
            dir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
                ?.mapNotNull(::readEntry)
                ?.forEach { (agentId, metadata) -> cache[agentId] = metadata }
            loaded = true
        }
    }

    /** One stored agent's metadata, or null for a file that is not a readable entry (skipped). */
    private fun readEntry(file: File): Pair<String, JsonObject>? {
        val agentId = runCatching { String(DECODER.decode(file.name.removeSuffix(SUFFIX))) }.getOrNull() ?: return null
        val metadata = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        return agentId to metadata
    }

    private fun fileFor(agentId: String) = File(dir, ENCODER.encodeToString(agentId.toByteArray()) + SUFFIX)

    companion object {
        const val METADATA_KEY = "metadata"

        /** Where the sidecar lives inside a local backend directory. */
        fun inLocalBackend(localBackendDir: File) = AgentMetadataSidecar(File(localBackendDir, "meridian/agent-metadata"))

        private const val SUFFIX = ".json"
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()
    }
}
