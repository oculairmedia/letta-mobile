package com.letta.mobile.data.swarm

import com.letta.mobile.data.serialization.LettaJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The outbox as one JSON file per message under [directory] (the device
 * profile's `outbox/`), written atomically and readable by the current user only, since entries
 * hold message text. Files are read with a size cap before decoding; an unreadable one is skipped
 * by [list] rather than failing every send.
 */
class FileSwarmOutbox(private val directory: Path) : SwarmOutbox {
    override suspend fun get(id: ClientMessageId): OutboxEntry? = withContext(Dispatchers.IO) {
        read(fileOf(id))
    }

    override suspend fun put(entry: OutboxEntry) = withContext(Dispatchers.IO) {
        writePrivately(fileOf(entry.id), LettaJson.persisted.encodeToString(OutboxEntry.serializer(), entry))
    }

    override suspend fun remove(id: ClientMessageId) {
        withContext(Dispatchers.IO) { Files.deleteIfExists(fileOf(id)) }
    }

    override suspend fun list(): List<OutboxEntry> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(directory)) return@withContext emptyList()
        Files.list(directory).use { files ->
            files.iterator().asSequence().filter { it.fileName.toString().endsWith(SUFFIX) }.toList()
        }.mapNotNull(::readOrNull).sortedBy { it.createdAtMs }
    }

    private fun fileOf(id: ClientMessageId): Path = directory.resolve("${id.value}$SUFFIX")

    private fun read(file: Path): OutboxEntry? {
        if (!Files.isRegularFile(file)) return null
        check(Files.size(file) <= MAX_ENTRY_BYTES) { "outbox entry $file is larger than $MAX_ENTRY_BYTES bytes" }
        return LettaJson.persisted.decodeFromString(OutboxEntry.serializer(), String(Files.readAllBytes(file), Charsets.UTF_8))
    }

    private fun readOrNull(file: Path): OutboxEntry? = try {
        read(file)
    } catch (expected: SerializationException) {
        null
    } catch (expectedOversized: IllegalStateException) {
        null
    }

    private companion object {
        const val SUFFIX = ".json"

        /** A full-size message JSON-escaped, with room for the entry's other fields. */
        const val MAX_ENTRY_BYTES = 512L * 1024L
    }
}
