package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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

    private fun backendDirectory(backendId: String): Path =
        rootDirectory.resolve(backendId.sha256PathComponent())

    private fun snapshotFile(scope: TimelineScope): Path =
        backendDirectory(scope.backendId)
            .resolve(
                "${scope.agentId.orEmpty().sha256PathComponent()}__" +
                    "${scope.conversationId.sha256PathComponent()}.json",
            )

    private fun String.sha256PathComponent(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encodeToByteArray())
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0f])
            }
        }
    }

    companion object {
        private const val HEX_DIGITS = "0123456789abcdef"

        fun defaultRootDirectory(): Path = defaultDesktopStateDirectory().resolve("timeline_snapshots")
    }
}
