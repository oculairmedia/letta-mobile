package com.letta.mobile.data.canvas

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/*
 * `notebook-canvases.json`: which notebook last held each canvas, so a lookup after a restart can
 * try one notebook instead of scanning all of them. It is only a hint. Every use is confirmed
 * against that notebook's own metadata, and a missing, stale or unreadable file just means a scan,
 * as before. It is rewritten whole with one atomic rename; two stores writing at once can only
 * lose a hint, never corrupt one.
 */

internal const val CANVAS_HINTS_FILE = "notebook-canvases.json"

private val HINTS = MapSerializer(String.serializer(), String.serializer())

/** Canvas id to notebook key, or empty when the file is missing or unreadable. */
internal fun Path.readCanvasHints(): Map<String, String> = runCatching {
    val file = resolve(CANVAS_HINTS_FILE)
    if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return emptyMap()
    Json.decodeFromString(HINTS, Files.readString(file, UTF_8)).filterValues { it.isNotebookKey() }
}.getOrDefault(emptyMap())

/** Replace the hints file with [hints]. A failure only costs a scan on the next start. */
internal fun Path.writeCanvasHints(hints: Map<String, String>) {
    runCatching {
        val temp = Files.createTempFile(this, ".notebook-canvases-", ".tmp")
        try {
            Files.writeString(temp, Json.encodeToString(HINTS, hints), UTF_8)
            Files.move(temp, resolve(CANVAS_HINTS_FILE), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
