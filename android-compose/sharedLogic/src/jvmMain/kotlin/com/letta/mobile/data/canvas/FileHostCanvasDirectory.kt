package com.letta.mobile.data.canvas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The host's canvas directory in one JSON file beside the relay's topics, rewritten whole and
 * atomically on each new entry. Entries are few (one per canvas an agent has used) and only ever
 * added, so a small file is enough, and a crash mid-write leaves the previous file intact.
 */
class FileHostCanvasDirectory(private val file: Path) : HostCanvasDirectory {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val serializer = ListSerializer(HostCanvasEntry.serializer())
    private var loaded: MutableList<HostCanvasEntry>? = null

    override suspend fun get(canvasId: String): HostCanvasEntry? = io { entries().firstOrNull { it.canvasId == canvasId } }

    override suspend fun forConversation(conversationId: String): HostCanvasEntry? =
        io { entries().firstOrNull { it.conversationId == conversationId } }

    override suspend fun putIfAbsent(entry: HostCanvasEntry): HostCanvasEntry = io {
        val entries = entries()
        existing(entries, entry)?.let { return@io it }
        entries += entry
        write(entries)
        entry
    }

    override suspend fun all(): List<HostCanvasEntry> = io { entries().toList() }

    private fun entries(): MutableList<HostCanvasEntry> = loaded ?: read().also { loaded = it }

    private fun read(): MutableList<HostCanvasEntry> =
        if (Files.exists(file)) json.decodeFromString(serializer, Files.readString(file)).toMutableList() else mutableListOf()

    private fun write(entries: List<HostCanvasEntry>) {
        Files.createDirectories(file.toAbsolutePath().parent)
        val temp = Files.createTempFile(file.toAbsolutePath().parent, "canvases", ".tmp")
        Files.writeString(temp, json.encodeToString(serializer, entries))
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private suspend fun <T> io(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }
}
