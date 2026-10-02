package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.util.Telemetry
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object DesktopTimelineSnapshotMaintenance {
    suspend fun clear(backendDirectory: Path, backendId: String): Unit = withContext(Dispatchers.IO) {
        if (Files.exists(backendDirectory)) {
            Files.walk(backendDirectory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).toList().forEach { Files.deleteIfExists(it) }
            }
        }
        Telemetry.event("DesktopTimelineStore", "clearForBackend", "backendId" to backendId)
    }

    suspend fun prune(
        backendDirectory: Path,
        backendId: String,
        maxRetainedConversations: Int,
    ): Unit = withContext(Dispatchers.IO) {
        if (!Files.exists(backendDirectory)) return@withContext
        if (maxRetainedConversations <= 0) {
            clear(backendDirectory, backendId)
            return@withContext
        }
        val snapshotFiles = Files.list(backendDirectory).use { paths ->
            paths.filter { it.toString().endsWith(".json") }.toList()
        }
        val decodedSnapshots = snapshotFiles.mapNotNull { file ->
            runCatching { Files.readString(file) }.getOrNull()
                ?.let(TimelineSnapshotCodec::decode)
                ?.let { file to it.writtenAtMillis }
        }
        val corruptSnapshots = snapshotFiles - decodedSnapshots.map { it.first }.toSet()
        corruptSnapshots.forEach { Files.deleteIfExists(it) }
        val excess = decodedSnapshots.sortedByDescending { it.second }.map { it.first }.drop(maxRetainedConversations)
        excess.forEach { Files.deleteIfExists(it) }
        deleteOrphanImageDirectories(backendDirectory)
        if (excess.isNotEmpty()) {
            Telemetry.event(
                "DesktopTimelineStore", "prune",
                "backendId" to backendId,
                "prunedCount" to excess.size,
                "remainingCount" to maxRetainedConversations,
            )
        }
    }

    /** Image bodies belong to their conversation's snapshot and go when it goes. */
    private fun deleteOrphanImageDirectories(backendDirectory: Path) {
        val suffix = DesktopTimelineImageBodies.DIRECTORY_SUFFIX
        val orphans = Files.list(backendDirectory).use { paths ->
            paths.filter { path ->
                val name = path.fileName.toString()
                name.endsWith(suffix) && Files.isDirectory(path) &&
                    !Files.exists(path.resolveSibling(name.removeSuffix(suffix) + ".json"))
            }.toList()
        }
        orphans.forEach(DesktopTimelineImageBodies::deleteDirectory)
    }
}
