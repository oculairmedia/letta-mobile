package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.TurnContextRecovery
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Removes zero-content assistant records from the active local-backend context.
 *
 * The append-only messages.jsonl transcript remains unchanged. Only IDs whose
 * stored role is exactly "assistant" and whose content array is exactly empty
 * are removed from conversation.json.
 */
class LocalBackendEmptyAssistantRecovery(
    private val baseDir: File,
) : TurnContextRecovery {
    private val json = Json { ignoreUnknownKeys = true }
    private val support = LocalBackendStoreSupport(baseDir, LocalBackendAdminStore.DEFAULT_MODEL_ENDPOINT)
    private val scans = mutableMapOf<String, TranscriptScan>()
    private val lock = Any()

    override suspend fun recover(agentId: String, conversationId: String): List<String> =
        synchronized(lock) {
            recoverLocked(agentId, conversationId)
        }

    private fun recoverLocked(agentId: String, conversationId: String): List<String> {
        val dir = conversationDir(agentId, conversationId)
        val conversationFile = File(dir, "conversation.json")
        val transcriptFile = File(dir, "messages.jsonl")
        if (!conversationFile.isFile || !transcriptFile.isFile) return emptyList()

        val conversation = parseObject(conversationFile) ?: return emptyList()
        if (conversation["agent_id"]?.jsonPrimitive?.content != agentId) return emptyList()
        if (conversation["id"]?.jsonPrimitive?.content != conversationId) return emptyList()

        val activeIds = conversation["in_context_message_ids"] as? JsonArray ?: return emptyList()
        val emptyAssistantIds = scanEmptyAssistantIds(transcriptFile)
        val removed = activeIds.mapNotNull { element ->
            element.jsonPrimitive.content.takeIf(emptyAssistantIds::contains)
        }
        if (removed.isEmpty()) return emptyList()

        val retained = JsonArray(activeIds.filterNot { it.jsonPrimitive.content in emptyAssistantIds })
        val updated = JsonObject(
            conversation.toMutableMap().apply {
                this["in_context_message_ids"] = retained
            },
        )
        atomicWrite(conversationFile, updated.toString())
        return removed
    }

    private fun conversationDir(agentId: String, conversationId: String): File {
        val key = support.conversationKey(conversationId, agentId)
        return File(File(baseDir, "conversations"), support.b64UrlEncode(key))
    }

    private fun parseObject(file: File): JsonObject? = runCatching {
        json.parseToJsonElement(file.readText()).jsonObject
    }.getOrNull()

    private fun scanEmptyAssistantIds(file: File): Set<String> {
        val key = file.absolutePath
        val previous = scans[key]
        val canContinue = previous != null && file.length() >= previous.bytesRead
        val state = if (canContinue) previous else TranscriptScan()
        if (file.length() == state.bytesRead) return state.emptyAssistantIds

        RandomAccessFile(file, "r").use { input ->
            input.seek(state.bytesRead)
            while (true) {
                val line = input.readLine() ?: break
                emptyAssistantId(line)?.let(state.emptyAssistantIds::add)
            }
            state.bytesRead = input.filePointer
        }
        scans[key] = state
        return state.emptyAssistantIds
    }

    private fun emptyAssistantId(line: String): String? {
        val envelope = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        val message = (envelope["message"] as? JsonObject) ?: envelope
        if (message["role"]?.jsonPrimitive?.content != "assistant") return null
        val content = (message["content"] ?: message["parts"]) as? JsonArray ?: return null
        if (content.isNotEmpty()) return null
        return message["id"]?.jsonPrimitive?.content
    }

    private fun atomicWrite(file: File, text: String) {
        val path = file.toPath()
        val previousMtime = Files.getLastModifiedTime(path).toMillis()
        val temp = path.resolveSibling(".${path.fileName}.repair-${UUID.randomUUID()}")
        val bytes = "$text\n".toByteArray(Charsets.UTF_8)
        try {
            FileChannel.open(
                temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE_NEW,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.setLastModifiedTime(path, FileTime.fromMillis(maxOf(System.currentTimeMillis(), previousMtime + 1L)))
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private data class TranscriptScan(
        var bytesRead: Long = 0L,
        val emptyAssistantIds: MutableSet<String> = linkedSetOf(),
    )
}
