package com.letta.mobile.data.subagents

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * letta-mobile-lgns8.22.8: JSON-file-backed [SubagentRegistryStore].
 *
 * Deliberately the same shape as `FilePairedPeerStore` — the existing
 * controller durable-state precedent — rather than a new database: fsync'd
 * temp file + atomic rename, so a crash mid-write can never expose a partially
 * written registry through the durable name. The file holds no secrets, only
 * chip identity/provenance/lifecycle.
 *
 * A corrupt or truncated file loads as empty (the registry telemeters
 * `rehydrate.failed`) so a bad file can never stop the controller booting.
 */
class FileSubagentRegistryStore(private val path: Path) : SubagentRegistryStore {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    override fun load(): List<SubagentChipRecord> {
        if (!Files.exists(path)) return emptyList()
        val text = runCatching { String(Files.readAllBytes(path), Charsets.UTF_8) }.getOrNull()
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SubagentChipRecord>>(text) }
            .getOrElse { emptyList() }
    }

    @Synchronized
    override fun save(records: List<SubagentChipRecord>) {
        path.parent?.let(Files::createDirectories)
        val temp = path.resolveSibling(".${path.fileName}.tmp-${UUID.randomUUID()}")
        val bytes = json.encodeToString(records).toByteArray(Charsets.UTF_8)

        FileChannel.open(
            temp,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
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

        // Best-effort parent-directory fsync so the rename itself is durable.
        path.parent?.let { parent ->
            try {
                FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Exception) {
                // Unsupported on some platforms; file fsync + atomic rename stand.
            }
        }
    }
}
