package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineStore
import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.timelineCurrentTimeMillis
import com.letta.mobile.util.Telemetry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** CursorWindow-safe Room persistence using metadata heads and bounded BLOB chunks. */
class RoomConfirmedTimelineStore(
    private val database: LettaDatabase,
) : ConfirmedTimelineStore {
    private val dao = database.confirmedTimelineSnapshotDao()

    override suspend fun readSnapshot(scope: TimelineScope): StoredTimelineEnvelope? =
        readSnapshotResult(scope).snapshot

    override suspend fun readSnapshotResult(scope: TimelineScope): ConfirmedTimelineReadResult = withContext(Dispatchers.IO) {
        val start = timelineCurrentTimeMillis()
        val head = dao.getHeadMetadata(scope.backendId, scope.conversationId)
            ?: return@withContext ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.MISSING)
        if (!head.matches(scope)) {
            return@withContext ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.METADATA_INVALID)
        }

        val activeId = head.activeManifestId
            ?: return@withContext ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.MISSING)
        when (val active = readManifest(scope, activeId, head.highWaterRevision)) {
            is ManifestRead.Valid -> {
                reportRead(scope, active.envelope, active.byteLength, start, fallback = false)
                ConfirmedTimelineReadResult.Active(active.envelope)
            }
            is ManifestRead.Invalid -> {
                val fallbackId = head.fallbackManifestId
                val fallback = fallbackId
                    ?.takeUnless { it == activeId }
                    ?.let { readManifest(scope, it, head.highWaterRevision) }
                if (fallback is ManifestRead.Valid) {
                    reportRead(scope, fallback.envelope, fallback.byteLength, start, fallback = true)
                    ConfirmedTimelineReadResult.Fallback(fallback.envelope, active.failure)
                } else {
                    Telemetry.event(
                        "RoomTimelineStore", "readSnapshot.reconciliationRequired",
                        "backendId" to scope.backendId,
                        "conversationId" to scope.conversationId,
                        "failure" to active.failure.name,
                        level = Telemetry.Level.WARN,
                    )
                    ConfirmedTimelineReadResult.ReconciliationRequired(active.failure)
                }
            }
        }
    }

    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean = withContext(Dispatchers.IO) {
        val scope = envelope.scope
        val writtenAt = envelope.writtenAtMillis.takeIf { it > 0 } ?: timelineCurrentTimeMillis()
        val normalized = envelope.copy(writtenAtMillis = writtenAt)
        val payload = TimelineSnapshotCodec.encode(normalized).toByteArray(StandardCharsets.UTF_8)
        require(payload.size.toLong() <= MAX_PAYLOAD_BYTES) { "Snapshot exceeds bounded storage limit" }

        val manifestId = UUID.randomUUID().toString()
        val chunks = payload.asListOfChunks(manifestId)
        val manifest = ConfirmedTimelineSnapshotManifestEntity(
            manifestId = manifestId,
            backendId = scope.backendId,
            conversationId = scope.conversationId,
            agentId = scope.agentId,
            revision = normalized.revision,
            schemaVersion = normalized.schemaVersion,
            byteLength = payload.size.toLong(),
            chunkCount = chunks.size,
            sha256 = sha256(payload),
            writtenAtMillis = writtenAt,
        )

        // Phase one: the complete body is committed without making it visible to readers.
        database.withTransaction {
            dao.insertManifest(manifest)
            chunks.chunked(CHUNK_INSERT_BATCH).forEach { batch -> dao.insertChunks(batch) }
        }

        // Validate exactly through the production bounded read path before publishing the head.
        val staged = readManifest(scope, manifestId, normalized.revision)
        if (staged !is ManifestRead.Valid || staged.envelope != normalized) {
            dao.deleteManifest(manifestId)
            return@withContext false
        }

        // Phase two: atomically retain the previous active and swap the metadata-only head.
        val swapped = database.withTransaction {
            val existing = dao.getHeadMetadata(scope.backendId, scope.conversationId)
            if (existing != null && existing.highWaterRevision >= normalized.revision) {
                false
            } else {
                val priorActive = existing?.activeManifestId.takeIf { existing?.agentId == scope.agentId }
                dao.replaceHead(
                    ConfirmedTimelineSnapshotHeadEntity(
                        backendId = scope.backendId,
                        conversationId = scope.conversationId,
                        agentId = scope.agentId,
                        activeManifestId = manifestId,
                        fallbackManifestId = priorActive,
                        highWaterRevision = normalized.revision,
                        writtenAtMillis = writtenAt,
                    )
                )
                true
            }
        }
        if (!swapped) {
            dao.deleteManifest(manifestId)
            reportStaleWrite(scope, envelope.revision)
            return@withContext false
        }

        // Deletes only payloads not referenced by any active/fallback head, including abandoned stages.
        dao.deleteOrphanManifestsForBackend(scope.backendId)
        Telemetry.event(
            "RoomTimelineStore", "writeSnapshot.success",
            "backendId" to scope.backendId,
            "conversationId" to scope.conversationId,
            "revision" to envelope.revision,
            "eventCount" to envelope.events.size,
            "byteSize" to payload.size,
            "chunkCount" to chunks.size,
        )
        true
    }

    override suspend fun deleteSnapshot(scope: TimelineScope) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.deleteHead(scope.backendId, scope.conversationId)
            dao.deleteManifestsForScope(scope.backendId, scope.conversationId)
        }
    }

    override suspend fun clearForBackend(backendId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.clearHeadsForBackend(backendId)
            dao.clearManifestsForBackend(backendId)
        }
    }

    override suspend fun prune(backendId: String, maxRetainedConversations: Int) = withContext(Dispatchers.IO) {
        database.withTransaction {
            if (maxRetainedConversations <= 0) {
                dao.clearHeadsForBackend(backendId)
                dao.clearManifestsForBackend(backendId)
            } else {
                dao.pruneHeads(backendId, maxRetainedConversations)
                dao.deleteOrphanManifestsForBackend(backendId)
            }
        }
    }

    private suspend fun readManifest(
        scope: TimelineScope,
        manifestId: String,
        maximumRevision: Long,
    ): ManifestRead {
        val manifest = dao.getManifest(manifestId)
            ?: return ManifestRead.Invalid(SnapshotReadFailure.MANIFEST_MISSING)
        if (!manifest.matches(scope)) return ManifestRead.Invalid(SnapshotReadFailure.SCOPE_MISMATCH)
        if (manifest.revision > maximumRevision || manifest.revision < 0L) {
            return ManifestRead.Invalid(SnapshotReadFailure.REVISION_MISMATCH)
        }
        if (manifest.schemaVersion !in 1..StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION) {
            return ManifestRead.Invalid(SnapshotReadFailure.SCHEMA_MISMATCH)
        }
        if (!manifest.hasBoundedShape()) return ManifestRead.Invalid(SnapshotReadFailure.METADATA_INVALID)

        val output = ByteArrayOutputStream(manifest.byteLength.toInt())
        val digest = MessageDigest.getInstance(SHA_256)
        repeat(manifest.chunkCount) { index ->
            coroutineContext.ensureActive()
            val chunk = dao.getChunk(manifestId, index)
                ?: return ManifestRead.Invalid(SnapshotReadFailure.CHUNK_MISSING)
            val expectedSize = manifest.expectedChunkSize(index)
            if (chunk.size != expectedSize || chunk.size > CHUNK_SIZE_BYTES) {
                return ManifestRead.Invalid(SnapshotReadFailure.CHUNK_INVALID)
            }
            digest.update(chunk)
            output.write(chunk)
        }
        val bytes = output.toByteArray()
        if (bytes.size.toLong() != manifest.byteLength) {
            return ManifestRead.Invalid(SnapshotReadFailure.LENGTH_MISMATCH)
        }
        if (digest.digest().toHex() != manifest.sha256.lowercase()) {
            return ManifestRead.Invalid(SnapshotReadFailure.CHECKSUM_MISMATCH)
        }
        val payload = bytes.decodeUtf8Strict()
            ?: return ManifestRead.Invalid(SnapshotReadFailure.CORRUPT_ENCODING)
        val decoded = TimelineSnapshotCodec.decode(payload)
            ?: return ManifestRead.Invalid(SnapshotReadFailure.CORRUPT_ENCODING)
        if (decoded.scope != scope) return ManifestRead.Invalid(SnapshotReadFailure.SCOPE_MISMATCH)
        if (decoded.revision != manifest.revision) return ManifestRead.Invalid(SnapshotReadFailure.REVISION_MISMATCH)
        if (decoded.schemaVersion != manifest.schemaVersion) return ManifestRead.Invalid(SnapshotReadFailure.SCHEMA_MISMATCH)
        return ManifestRead.Valid(decoded, manifest.byteLength)
    }

    private fun reportRead(
        scope: TimelineScope,
        envelope: StoredTimelineEnvelope,
        byteLength: Long,
        start: Long,
        fallback: Boolean,
    ) {
        Telemetry.event(
            "RoomTimelineStore", if (fallback) "readSnapshot.fallback" else "readSnapshot.success",
            "backendId" to scope.backendId,
            "conversationId" to scope.conversationId,
            "revision" to envelope.revision,
            "eventCount" to envelope.events.size,
            "byteSize" to byteLength,
            "readDurationMs" to (timelineCurrentTimeMillis() - start),
        )
    }

    private fun reportStaleWrite(scope: TimelineScope, revision: Long) {
        Telemetry.event(
            "RoomTimelineStore", "writeSnapshot.staleRejected",
            "backendId" to scope.backendId,
            "conversationId" to scope.conversationId,
            "attemptedRevision" to revision,
            level = Telemetry.Level.WARN,
        )
    }

    private sealed interface ManifestRead {
        data class Valid(val envelope: StoredTimelineEnvelope, val byteLength: Long) : ManifestRead
        data class Invalid(val failure: SnapshotReadFailure) : ManifestRead
    }

    companion object {
        const val CHUNK_SIZE_BYTES = 128 * 1024
        private const val CHUNK_INSERT_BATCH = 32
        private const val MAX_CHUNK_COUNT = 2048
        private const val MAX_PAYLOAD_BYTES = CHUNK_SIZE_BYTES.toLong() * MAX_CHUNK_COUNT
        private const val SHA_256 = "SHA-256"
    }
}

private fun ConfirmedTimelineSnapshotHeadMetadata.matches(scope: TimelineScope): Boolean =
    backendId == scope.backendId && conversationId == scope.conversationId && agentId == scope.agentId &&
        highWaterRevision >= 0L

private fun ConfirmedTimelineSnapshotManifestEntity.matches(scope: TimelineScope): Boolean =
    backendId == scope.backendId && conversationId == scope.conversationId && agentId == scope.agentId

private fun ConfirmedTimelineSnapshotManifestEntity.hasBoundedShape(): Boolean {
    if (byteLength <= 0L || byteLength > RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES.toLong() * 2048L) return false
    val expectedCount = ((byteLength + RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES - 1) /
        RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES).toInt()
    return chunkCount == expectedCount && chunkCount in 1..2048 && sha256.length == 64
}

private fun ConfirmedTimelineSnapshotManifestEntity.expectedChunkSize(index: Int): Int =
    if (index < chunkCount - 1) RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES
    else (byteLength - (chunkCount - 1L) * RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES).toInt()

private fun ByteArray.asListOfChunks(manifestId: String): List<ConfirmedTimelineSnapshotChunkEntity> =
    (indices step RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES).mapIndexed { index, offset ->
        ConfirmedTimelineSnapshotChunkEntity(
            manifestId = manifestId,
            chunkIndex = index,
            payload = copyOfRange(offset, minOf(offset + RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES, size)),
        )
    }

private fun ByteArray.decodeUtf8Strict(): String? = runCatching {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
}.getOrNull()

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
