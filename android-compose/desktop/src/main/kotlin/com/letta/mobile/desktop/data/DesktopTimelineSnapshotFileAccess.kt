package com.letta.mobile.desktop.data

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

/** Owns blocking snapshot file access, revision arbitration, and atomic replacement. */
internal class DesktopTimelineSnapshotFileAccess(
    private val snapshotFile: (TimelineScope) -> Path,
) {
    private val scopeWriteLocks = ConcurrentHashMap<String, Any>()

    suspend fun read(scope: TimelineScope): StoredTimelineEnvelope? = withContext(Dispatchers.IO) {
        val startedAtMillis = timelineCurrentTimeMillis()
        SnapshotRead.from(snapshotFile(scope)).also { read ->
            read.report(scope, startedAtMillis)
        }.envelope
    }

    suspend fun write(envelope: StoredTimelineEnvelope): Boolean = withContext(Dispatchers.IO) {
        val scope = envelope.scope
        val file = snapshotFile(scope)
        val parent = requireNotNull(file.parent)
        val scopeLock = scopeWriteLocks.computeIfAbsent(scope.storageKey) { Any() }

        synchronized(scopeLock) {
            runCatching {
                Files.createDirectories(parent)
                val candidate = envelope.withWriteTimestamp()
                ExistingSnapshot.from(file)
                    .dispositionFor(candidate)
                    .persist(file, scope)
            }.getOrElse { error ->
                Telemetry.error("DesktopTimelineStore", "writeSnapshot.failed", error, "scope" to scope.storageKey)
                false
            }
        }
    }

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
                    onFailure = { error -> IoFailure(error as? IOException ?: IOException(error)) },
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
                if (envelope.revision >= candidate.revision) {
                    SnapshotWriteDisposition.Stale(candidate, envelope)
                } else {
                    SnapshotWriteDisposition.Persist(candidate)
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
                val temporary = Files.createTempFile(requireNotNull(file.parent), "snapshot-", ".tmp")
                try {
                    Files.writeString(temporary, payload)
                    replaceSnapshot(temporary, file)
                } finally {
                    Files.deleteIfExists(temporary)
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
        private fun replaceSnapshot(source: Path, destination: Path) {
            runCatching {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
