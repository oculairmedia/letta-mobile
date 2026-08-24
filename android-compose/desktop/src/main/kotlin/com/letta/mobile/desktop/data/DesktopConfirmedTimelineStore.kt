package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.nio.file.Files
import java.nio.file.Path
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
    private val fileAccess = DesktopTimelineSnapshotFileAccess(::snapshotFile)

    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? =
        fileAccess.read(scope)

    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean =
        fileAccess.write(envelope)

    override suspend fun deleteSnapshot(scope: TimelineScope): Unit = withContext(Dispatchers.IO) {
        Files.deleteIfExists(snapshotFile(scope))
    }

    override suspend fun clearForBackend(backendId: String): Unit =
        DesktopTimelineSnapshotMaintenance.clear(backendDirectory(backendId), backendId)

    override suspend fun prune(backendId: String, maxRetainedConversations: Int): Unit =
        DesktopTimelineSnapshotMaintenance.prune(
            backendDirectory = backendDirectory(backendId),
            backendId = backendId,
            maxRetainedConversations = maxRetainedConversations,
        )

    private fun backendDirectory(backendId: String): Path = rootDirectory.resolve(sanitize(backendId))

    private fun snapshotFile(scope: TimelineScope): Path =
        backendDirectory(scope.backendId)
            .resolve(sanitize("${scope.agentId.orEmpty()}__${scope.conversationId}") + ".json")

    private fun sanitize(value: String): String = value.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    companion object {
        fun defaultRootDirectory(): Path = defaultDesktopStateDirectory().resolve("timeline_snapshots")
    }
}
