package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineImageBodies
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.StoredImageBodyReference
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.util.Telemetry
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop file-backed implementation of [ConfirmedTimelineStore].
 *
 * Persists confirmed timeline snapshots as atomic JSON files organized by backend
 * identifier and conversation id under the desktop state directory.
 *
 * Images too large for the snapshot's inline thumbnail are kept as content-addressed bodies in a
 * `.images` directory beside the conversation's snapshot. They are deleted with the snapshot (delete,
 * prune, backend clear), and a body no snapshot names any more is reclaimed once it is older than
 * [imageBodyGrace], which covers the window between writing a body and writing the snapshot naming it.
 */
class DesktopConfirmedTimelineStore(
    private val rootDirectory: Path = defaultRootDirectory(),
    private val imageBodyGrace: Duration = DEFAULT_IMAGE_BODY_GRACE,
) : ConfirmedTimelineStore, ConfirmedTimelineImageBodies {
    private val fileAccess = DesktopTimelineSnapshotFileAccess(::snapshotFile, ImageBodyRetention())

    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? =
        fileAccess.read(scope)

    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean =
        fileAccess.write(envelope)

    override suspend fun deleteSnapshot(scope: TimelineScope): Unit = withContext(Dispatchers.IO) {
        fileAccess.withScopeLock(scope) {
            Files.deleteIfExists(snapshotFile(scope))
            DesktopTimelineImageBodies.deleteDirectory(imageDirectory(scope))
        }
    }

    override suspend fun persistImage(scope: TimelineScope, base64: String): StoredImageBodyReference =
        withContext(Dispatchers.IO) {
            val decoded = DesktopTimelineImageBodies.decode(base64)
            val reference = decoded.reference
            val target = DesktopTimelineImageBodies.bodyFile(imageDirectory(scope), reference.sha256)
            if (DesktopTimelineImageBodies.holds(target, reference)) {
                fileAccess.withScopeLock(scope) { DesktopTimelineImageBodies.touch(target) }
            } else {
                // Absent, or present but corrupt: a fresh copy replaces it atomically.
                val staged = DesktopTimelineImageBodies.stage(target.parent, decoded.bytes)
                try {
                    fileAccess.withScopeLock(scope) { DesktopTimelineImageBodies.publish(staged, target) }
                } finally {
                    Files.deleteIfExists(staged)
                }
            }
            reference
        }

    override suspend fun resolveImage(scope: TimelineScope, reference: StoredImageBodyReference): String? =
        withContext(Dispatchers.IO) {
            DesktopTimelineImageBodies.read(
                DesktopTimelineImageBodies.bodyFile(imageDirectory(scope), reference.sha256), reference,
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

    private fun backendDirectory(backendId: String): Path =
        rootDirectory.resolve(backendId.sha256PathComponent())

    private fun imageDirectory(scope: TimelineScope): Path {
        val snapshot = snapshotFile(scope)
        return snapshot.resolveSibling(
            snapshot.fileName.toString().removeSuffix(".json") + DesktopTimelineImageBodies.DIRECTORY_SUFFIX,
        )
    }

    private inner class ImageBodyRetention : DesktopSnapshotWriteHooks {
        /**
         * A loop that could not resolve a body (missing, or past the hydration budget) writes that
         * image back size-only. Keep the reference the previous snapshot held for the same image
         * rather than dropping the only way back to its bytes.
         */
        override fun prepare(existing: StoredTimelineEnvelope?, candidate: StoredTimelineEnvelope): StoredTimelineEnvelope {
            if (existing == null) return candidate
            val sizeOnly = candidate.events.any { event ->
                event.attachments.any { it.thumbnailBase64 == null && it.bodyReference == null }
            }
            if (!sizeOnly) return candidate
            val previous = existing.events.associateBy { it.serverId }
            var changed = false
            val events = candidate.events.map { event ->
                val prior = previous[event.serverId] ?: return@map event
                val attachments = event.attachments.mapIndexed { index, pointer ->
                    val old = prior.attachments.getOrNull(index)
                    if (pointer.thumbnailBase64 == null && pointer.bodyReference == null &&
                        old?.bodyReference != null && old.mediaType == pointer.mediaType &&
                        old.byteSize == pointer.byteSize
                    ) {
                        changed = true
                        old
                    } else {
                        pointer
                    }
                }
                if (attachments == event.attachments) event else event.copy(attachments = attachments)
            }
            return if (changed) candidate.copy(events = events) else candidate
        }

        override fun written(written: StoredTimelineEnvelope) {
            val referenced = written.events.flatMapTo(HashSet()) { event ->
                event.attachments.mapNotNull { it.bodyReference?.sha256 }
            }
            try {
                val reclaimed = DesktopTimelineImageBodies.sweep(
                    imageDirectory(written.scope), referenced, Instant.now().minus(imageBodyGrace),
                )
                if (reclaimed > 0) {
                    Telemetry.event(
                        "DesktopTimelineStore", "imageBodies.reclaimed",
                        "scope" to written.scope.storageKey,
                        "count" to reclaimed,
                    )
                }
            } catch (error: IOException) {
                // The snapshot is already durable; an unreclaimed body is retried on the next write.
                Telemetry.error("DesktopTimelineStore", "imageBodies.sweepFailed", error, "scope" to written.scope.storageKey)
            }
        }
    }

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
        private val DEFAULT_IMAGE_BODY_GRACE: Duration = Duration.ofMinutes(10)

        fun defaultRootDirectory(): Path = defaultDesktopStateDirectory().resolve("timeline_snapshots")
    }
}
