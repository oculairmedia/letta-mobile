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
import java.util.concurrent.ConcurrentHashMap
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
    private val scopeWriteLocks = ConcurrentHashMap<String, Any>()

    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? = withContext(Dispatchers.IO) {
        val start = timelineCurrentTimeMillis()
        val file = snapshotFile(scope)
        SnapshotRead.from(file).also { read ->
            read.report(scope, start)
        }.envelope
    }

    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean = withContext(Dispatchers.IO) {
        val scope = envelope.scope
        val file = snapshotFile(scope)
        val parent = requireNotNull(file.parent)
        val scopeLock = scopeWriteLocks.computeIfAbsent(scope.storageKey) { Any() }

        synchronized(scopeLock) {
            try {
                Files.createDirectories(parent)
                val candidate = envelope.withWriteTimestamp()
                ExistingSnapshot.from(file)
                    .dispositionFor(candidate)
                    .persist(file, scope)
            } catch (e: Exception) {
                Telemetry.error("DesktopTimelineStore", "writeSnapshot.failed", e, "scope" to scope.storageKey)
                false
            }
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

    private fun backendDirectory(backendId: String): Path = rootDirectory.resolve(sanitize(backendId))

    private fun snapshotFile(scope: TimelineScope): Path {
        val backendDir = backendDirectory(scope.backendId)
        val safeName = sanitize("${scope.agentId.orEmpty()}__${scope.conversationId}") + ".json"
        return backendDir.resolve(safeName)
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private sealed interface SnapshotRead {
        val envelope: StoredTimelineEnvelope?

        fun report(scope: TimelineScope, startedAtMillis: Long)

        data object Missing : SnapshotRead {
            override val envelope: StoredTimelineEnvelope? = null

            override fun report(scope: TimelineScope, startedAtMillis: Long) = Unit
        }

        data class IoFailure(private val exception: IOException) : SnapshotRead {
            override val envelope: StoredTimelineEnvelope? = null

            override fun report(scope: TimelineScope, startedAtMillis: Long) {
                Telemetry.error("DesktopTimelineStore", "readSnapshot.ioError", exception, "scope" to scope.storageKey)
            }
        }

        data class Decoded(
            override val envelope: StoredTimelineEnvelope,
            private val payloadSize: Int,
        ) : SnapshotRead {
            override fun report(scope: TimelineScope, startedAtMillis: Long) {
                val now = timelineCurrentTimeMillis()
                Telemetry.event(
                    "DesktopTimelineStore", "readSnapshot.success",
                    "backendId" to scope.backendId,
                    "conversationId" to scope.conversationId,
                    "revision" to envelope.revision,
                    "eventCount" to envelope.events.size,
                    "byteSize" to payloadSize,
                    "readDurationMs" to now - startedAtMillis,
                    "ageMs" to (now - envelope.writtenAtMillis).coerceAtLeast(0),
                )
            }
        }

        data object Corrupt : SnapshotRead {
            override val envelope: StoredTimelineEnvelope? = null

            override fun report(scope: TimelineScope, startedAtMillis: Long) {
                Telemetry.event(
                    "DesktopTimelineStore", "readSnapshot.corruptRecovered",
                    "backendId" to scope.backendId,
                    "conversationId" to scope.conversationId,
                    level = Telemetry.Level.WARN,
                )
            }
        }

        companion object {
            fun from(file: Path): SnapshotRead = when {
                !Files.exists(file) -> Missing
                else -> runCatching { Files.readString(file) }.fold(
                    onSuccess = { payload ->
                        TimelineSnapshotCodec.decode(payload)?.let { Decoded(it, payload.length) } ?: Corrupt
                    },
                    onFailure = { exception -> IoFailure(exception as? IOException ?: IOException(exception)) },
                )
            }
        }
    }

    private sealed interface ExistingSnapshot {
        fun dispositionFor(candidate: StoredTimelineEnvelope): SnapshotWriteDisposition

        data object Replaceable : ExistingSnapshot {
            override fun dispositionFor(candidate: StoredTimelineEnvelope): SnapshotWriteDisposition =
                SnapshotWriteDisposition.Persist(candidate)
        }

        data class Current(private val envelope: StoredTimelineEnvelope) : ExistingSnapshot {
            override fun dispositionFor(candidate: StoredTimelineEnvelope): SnapshotWriteDisposition =
                when {
                    envelope.revision >= candidate.revision -> SnapshotWriteDisposition.Stale(candidate, envelope)
                    else -> SnapshotWriteDisposition.Persist(candidate)
                }
        }

        companion object {
            fun from(file: Path): ExistingSnapshot = SnapshotRead.from(file).let { snapshot ->
                (snapshot as? SnapshotRead.Decoded)?.let { Current(it.envelope) } ?: Replaceable
            }
        }
    }

    private sealed interface SnapshotWriteDisposition {
        fun persist(file: Path, scope: TimelineScope): Boolean

        data class Persist(private val envelope: StoredTimelineEnvelope) : SnapshotWriteDisposition {
            override fun persist(file: Path, scope: TimelineScope): Boolean {
                val payload = TimelineSnapshotCodec.encode(envelope)
                val tmp = Files.createTempFile(requireNotNull(file.parent), "snapshot-", ".tmp")
                try {
                    Files.writeString(tmp, payload)
                    replaceSnapshot(tmp, file)
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
                return true
            }
        }

        data class Stale(
            private val attempted: StoredTimelineEnvelope,
            private val existing: StoredTimelineEnvelope,
        ) : SnapshotWriteDisposition {
            override fun persist(file: Path, scope: TimelineScope): Boolean {
                Telemetry.event(
                    "DesktopTimelineStore", "writeSnapshot.staleRejected",
                    "backendId" to scope.backendId,
                    "conversationId" to scope.conversationId,
                    "existingRevision" to existing.revision,
                    "attemptedRevision" to attempted.revision,
                    level = Telemetry.Level.WARN,
                )
                return false
            }
        }
    }

    private fun StoredTimelineEnvelope.withWriteTimestamp(): StoredTimelineEnvelope =
        takeIf { it.writtenAtMillis > 0 } ?: copy(writtenAtMillis = timelineCurrentTimeMillis())

    companion object {
        fun defaultRootDirectory(): Path =
            defaultDesktopStateDirectory().resolve("timeline_snapshots")

        private fun replaceSnapshot(source: Path, destination: Path) {
            runCatching {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
