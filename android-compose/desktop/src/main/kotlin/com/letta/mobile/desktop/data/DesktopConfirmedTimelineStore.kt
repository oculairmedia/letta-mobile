package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.timelineCurrentTimeMillis
import com.letta.mobile.util.Telemetry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop file-backed implementation of [ConfirmedTimelineStore].
 *
 * Persists confirmed timeline snapshots as atomic JSON files organized by backend
 * identifier and conversation id under the desktop state directory.
 */
class DesktopConfirmedTimelineStore(
    private val rootDirectory: Path = defaultRootDirectory(),
) : ConfirmedTimelineStore {

    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? = withContext(Dispatchers.IO) {
        val start = timelineCurrentTimeMillis()
        val file = snapshotFile(scope)
        if (!Files.exists(file)) return@withContext null

        val payload = try {
            Files.readString(file)
        } catch (e: IOException) {
            Telemetry.error("DesktopTimelineStore", "readSnapshot.ioError", e, "scope" to scope.storageKey)
            return@withContext null
        }

        val decoded = TimelineSnapshotCodec.decode(payload)
        val durationMs = timelineCurrentTimeMillis() - start

        if (decoded != null) {
            val ageMs = (timelineCurrentTimeMillis() - decoded.writtenAtMillis).coerceAtLeast(0)
            Telemetry.event(
                "DesktopTimelineStore", "readSnapshot.success",
                "backendId" to scope.backendId,
                "conversationId" to scope.conversationId,
                "revision" to decoded.revision,
                "eventCount" to decoded.events.size,
                "byteSize" to payload.length,
                "readDurationMs" to durationMs,
                "ageMs" to ageMs,
            )
        } else {
            Telemetry.event(
                "DesktopTimelineStore", "readSnapshot.corruptRecovered",
                "backendId" to scope.backendId,
                "conversationId" to scope.conversationId,
                level = Telemetry.Level.WARN,
            )
        }

        decoded
    }

    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean = withContext(Dispatchers.IO) {
        val scope = envelope.scope
        val file = snapshotFile(scope)
        val parent = file.parent ?: return@withContext false

        try {
            Files.createDirectories(parent)

            if (Files.exists(file)) {
                val existingPayload = runCatching { Files.readString(file) }.getOrNull()
                val existing = existingPayload?.let { TimelineSnapshotCodec.decode(it) }
                if (existing != null && existing.revision >= envelope.revision) {
                    Telemetry.event(
                        "DesktopTimelineStore", "writeSnapshot.staleRejected",
                        "backendId" to scope.backendId,
                        "conversationId" to scope.conversationId,
                        "existingRevision" to existing.revision,
                        "attemptedRevision" to envelope.revision,
                        level = Telemetry.Level.WARN,
                    )
                    return@withContext false
                }
            }

            val writtenAt = if (envelope.writtenAtMillis > 0) envelope.writtenAtMillis else timelineCurrentTimeMillis()
            val toWrite = if (envelope.writtenAtMillis > 0) envelope else envelope.copy(writtenAtMillis = writtenAt)
            val payload = TimelineSnapshotCodec.encode(toWrite)

            val tmp = Files.createTempFile(parent, "snapshot-", ".tmp")
            try {
                Files.writeString(tmp, payload)
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }

            Telemetry.event(
                "DesktopTimelineStore", "writeSnapshot.success",
                "backendId" to scope.backendId,
                "conversationId" to scope.conversationId,
                "revision" to envelope.revision,
                "eventCount" to envelope.events.size,
                "byteSize" to payload.length,
            )
            true
        } catch (e: Exception) {
            Telemetry.error("DesktopTimelineStore", "writeSnapshot.failed", e, "scope" to scope.storageKey)
            false
        }
    }

    override suspend fun deleteSnapshot(scope: TimelineScope): Unit = withContext(Dispatchers.IO) {
        val file = snapshotFile(scope)
        Files.deleteIfExists(file)
        Telemetry.event(
            "DesktopTimelineStore", "deleteSnapshot",
            "backendId" to scope.backendId,
            "conversationId" to scope.conversationId,
        )
    }

    override suspend fun clearForBackend(backendId: String): Unit =
        DesktopTimelineSnapshotMaintenance.clear(backendDirectory(backendId), backendId)

    override suspend fun prune(backendId: String, maxRetainedConversations: Int): Unit =
        DesktopTimelineSnapshotMaintenance.prune(
            backendDirectory = backendDirectory(backendId),
            backendId = backendId,
            maxRetainedConversations = maxRetainedConversations,
        )

    private fun backendDirectory(backendId: String): Path {
        val safeBackend = sanitize(backendId)
        return rootDirectory.resolve(safeBackend)
    }

    private fun snapshotFile(scope: TimelineScope): Path {
        val backendDir = backendDirectory(scope.backendId)
        val safeName = sanitize("${scope.agentId.orEmpty()}__${scope.conversationId}") + ".json"
        return backendDir.resolve(safeName)
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    companion object {
        fun defaultRootDirectory(): Path =
            defaultDesktopStateDirectory().resolve("timeline_snapshots")
    }
}
