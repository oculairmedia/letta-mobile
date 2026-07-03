package com.letta.mobile.avatar.catalog

import com.letta.mobile.avatar.core.AvatarModel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * File-backed catalog store (`catalog.json`). Saves are atomic: written to a
 * sibling temp file and moved into place, so a crash mid-save never leaves a
 * truncated catalog. A missing file is an empty catalog, not an error.
 */
class JsonFileAvatarCatalogStore(
    private val file: Path,
) : AvatarCatalogStore {
    override suspend fun load(): List<AvatarModel> = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext emptyList()
        // Byte-level read/write: Files.readString/writeString are Java 11 and
        // absent from the Android runtime at minSdk 26.
        AvatarCatalogCodec.decode(Files.readAllBytes(file).decodeToString())
    }

    override suspend fun save(entries: List<AvatarModel>) = withContext(Dispatchers.IO) {
        file.parent?.let { Files.createDirectories(it) }
        val temp = file.resolveSibling("${file.fileName}.tmp")
        Files.write(temp, AvatarCatalogCodec.encode(entries).encodeToByteArray())
        try {
            Files.move(
                temp,
                file,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    }
}
