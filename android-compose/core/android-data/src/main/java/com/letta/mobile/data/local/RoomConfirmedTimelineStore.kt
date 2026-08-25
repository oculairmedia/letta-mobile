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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
        val startedAtMillis = timelineCurrentTimeMillis()
        val head = dao.getHeadMetadata(scope.backendId, scope.conversationId)
            ?: return@withContext ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.MISSING)
        if (!head.matches(scope)) {
            return@withContext ConfirmedTimelineReadResult.ReconciliationRequired(SnapshotReadFailure.METADATA_INVALID)
        }
        readFromHead(ReadRequest(scope, head, startedAtMillis))
    }

    private suspend fun readFromHead(request: ReadRequest): ConfirmedTimelineReadResult {
        val activeId = request.head.activeManifestId
            ?: return readWithoutActiveManifest(request)
        return when (val active = readManifest(request.activeManifest(activeId))) {
            is ManifestRead.Valid -> activeResult(request, active)
            is ManifestRead.Invalid -> fallbackOrReconcile(request, activeId, active.failure)
        }
    }

    private suspend fun readWithoutActiveManifest(request: ReadRequest): ConfirmedTimelineReadResult {
        val fallback = readFallbackManifest(request, excludedManifestId = null)
        return if (fallback == null) {
            request.reconciliation(SnapshotReadFailure.MISSING)
        } else {
            fallbackResult(request, fallback, SnapshotReadFailure.MISSING)
        }
    }

    private suspend fun fallbackOrReconcile(
        request: ReadRequest,
        activeManifestId: String,
        activeFailure: SnapshotReadFailure,
    ): ConfirmedTimelineReadResult {
        val fallback = readFallbackManifest(request, excludedManifestId = activeManifestId)
        if (fallback != null) return fallbackResult(request, fallback, activeFailure)
        reportReconciliation(request.scope, activeFailure)
        return request.reconciliation(activeFailure)
    }

    private suspend fun readFallbackManifest(
        request: ReadRequest,
        excludedManifestId: String?,
    ): ManifestRead.Valid? {
        val fallbackId = request.head.fallbackManifestId
            ?.takeUnless { it == excludedManifestId }
            ?: return null
        return readManifest(request.fallbackManifest(fallbackId)) as? ManifestRead.Valid
    }

    private fun activeResult(request: ReadRequest, read: ManifestRead.Valid): ConfirmedTimelineReadResult {
        reportRead(ReadObservation(request, read, ReadSource.ACTIVE))
        return ConfirmedTimelineReadResult.Active(read.envelope, request.head.highWaterRevision)
    }

    private fun fallbackResult(
        request: ReadRequest,
        read: ManifestRead.Valid,
        activeFailure: SnapshotReadFailure,
    ): ConfirmedTimelineReadResult {
        reportRead(ReadObservation(request, read, ReadSource.FALLBACK))
        return ConfirmedTimelineReadResult.Fallback(
            snapshot = read.envelope,
            activeFailure = activeFailure,
            highWaterRevision = request.head.highWaterRevision,
        )
    }

    override suspend fun writeSnapshot(envelope: StoredTimelineEnvelope): Boolean = withContext(Dispatchers.IO) {
        val scope = envelope.scope
        val writtenAt = envelope.writtenAtMillis.takeIf { it > 0 } ?: timelineCurrentTimeMillis()
        val normalized = envelope.copy(writtenAtMillis = writtenAt)
        val payload = TimelineSnapshotCodec.encode(normalized).toByteArray(StandardCharsets.UTF_8)
        require(payload.isNotEmpty() && payload.size.toLong() <= MAX_PAYLOAD_BYTES) {
            "Snapshot exceeds bounded storage limit"
        }

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
        var published = false

        try {
            // Phase one: commit the complete body without making it visible to readers.
            database.withTransaction {
                dao.insertManifest(manifest)
                chunks.chunked(CHUNK_INSERT_BATCH).forEach { batch ->
                    coroutineContext.ensureActive()
                    dao.insertChunks(batch)
                }
            }

            // Validate exactly through the production bounded read path before publishing the head.
            val staged = readManifest(
                ManifestRequest(
                    scope = scope,
                    manifestId = manifestId,
                    maximumRevision = normalized.revision,
                    revisionPolicy = RevisionPolicy.EXACT,
                )
            )
            if (staged !is ManifestRead.Valid || staged.envelope != normalized) {
                dao.deleteManifest(manifestId)
                return@withContext false
            }

            val observedHead = dao.getHeadMetadata(scope.backendId, scope.conversationId)
            val retainedFallback = observedHead?.takeIf { it.matches(scope) }?.let { head ->
                selectLastKnownGoodManifest(scope, head)
            }

            // Phase two: atomically retain the last-known-good body and swap only the metadata head.
            val swapped = database.withTransaction {
                val existing = dao.getHeadMetadata(scope.backendId, scope.conversationId)
                if (existing != null && existing.highWaterRevision >= normalized.revision) {
                    false
                } else {
                    val fallbackId = retainedFallback.takeIf { existing == observedHead }
                    dao.replaceHead(
                        ConfirmedTimelineSnapshotHeadEntity(
                            backendId = scope.backendId,
                            conversationId = scope.conversationId,
                            agentId = scope.agentId,
                            activeManifestId = manifestId,
                            fallbackManifestId = fallbackId,
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
            published = true

            // Delete only payloads not referenced by any active/fallback head, including abandoned stages.
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
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val isHead = dao.getHeadMetadata(scope.backendId, scope.conversationId)?.activeManifestId == manifestId
                if (!published && !isHead) dao.deleteManifest(manifestId)
            }
            throw cancelled
        } catch (failure: Throwable) {
            val isHead = dao.getHeadMetadata(scope.backendId, scope.conversationId)?.activeManifestId == manifestId
            if (!published && !isHead) {
                dao.deleteManifest(manifestId)
                throw failure
            }
            Telemetry.error(
                "RoomTimelineStore", "writeSnapshot.postPublishCleanupFailed", failure,
                "backendId" to scope.backendId,
                "conversationId" to scope.conversationId,
                "revision" to envelope.revision,
            )
            true
        }
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

    private suspend fun selectLastKnownGoodManifest(
        scope: TimelineScope,
        head: ConfirmedTimelineSnapshotHeadMetadata,
    ): String? {
        val request = ReadRequest(scope, head, startedAtMillis = 0L)
        val activeId = head.activeManifestId
        if (activeId != null && readManifest(request.activeManifest(activeId)) is ManifestRead.Valid) {
            return activeId
        }
        return head.fallbackManifestId
            ?.takeUnless { it == activeId }
            ?.takeIf { readManifest(request.fallbackManifest(it)) is ManifestRead.Valid }
    }

    private suspend fun readManifest(request: ManifestRequest): ManifestRead {
        val manifest = dao.getManifest(request.manifestId)
            ?: return ManifestRead.Invalid(SnapshotReadFailure.MANIFEST_MISSING)
        ManifestValidator.validateMetadata(manifest, request)?.let { failure ->
            return ManifestRead.Invalid(failure)
        }
        return when (val payload = readManifestPayload(manifest)) {
            is ManifestPayload.Valid -> decodeManifest(request.scope, manifest, payload.bytes)
            is ManifestPayload.Invalid -> ManifestRead.Invalid(payload.failure)
        }
    }

    private suspend fun readManifestPayload(manifest: ConfirmedTimelineSnapshotManifestEntity): ManifestPayload {
        val output = ByteArrayOutputStream(manifest.byteLength.toInt())
        val digest = MessageDigest.getInstance(SHA_256)
        repeat(manifest.chunkCount) { index ->
            coroutineContext.ensureActive()
            val chunk = dao.getChunk(manifest.manifestId, index)
                ?: return ManifestPayload.Invalid(SnapshotReadFailure.CHUNK_MISSING)
            ManifestValidator.validateChunk(manifest, index, chunk)?.let { failure ->
                return ManifestPayload.Invalid(failure)
            }
            digest.update(chunk)
            output.write(chunk)
        }
        return ManifestValidator.validatePayload(manifest, output.toByteArray(), digest.digest())
    }

    private fun decodeManifest(
        scope: TimelineScope,
        manifest: ConfirmedTimelineSnapshotManifestEntity,
        bytes: ByteArray,
    ): ManifestRead {
        val payload = bytes.decodeUtf8Strict()
            ?: return ManifestRead.Invalid(SnapshotReadFailure.CORRUPT_ENCODING)
        val envelope = TimelineSnapshotCodec.decode(payload)
            ?: return ManifestRead.Invalid(SnapshotReadFailure.CORRUPT_ENCODING)
        ManifestValidator.validateEnvelope(scope, manifest, envelope)?.let { failure ->
            return ManifestRead.Invalid(failure)
        }
        return ManifestRead.Valid(envelope, manifest.byteLength)
    }

    private fun reportRead(observation: ReadObservation) {
        Telemetry.event(
            "RoomTimelineStore", observation.source.telemetryEvent,
            "backendId" to observation.request.scope.backendId,
            "conversationId" to observation.request.scope.conversationId,
            "revision" to observation.read.envelope.revision,
            "eventCount" to observation.read.envelope.events.size,
            "byteSize" to observation.read.byteLength,
            "readDurationMs" to (timelineCurrentTimeMillis() - observation.request.startedAtMillis),
        )
    }

    private fun reportReconciliation(scope: TimelineScope, failure: SnapshotReadFailure) {
        Telemetry.event(
            "RoomTimelineStore", "readSnapshot.reconciliationRequired",
            "backendId" to scope.backendId,
            "conversationId" to scope.conversationId,
            "failure" to failure.name,
            level = Telemetry.Level.WARN,
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

    private data class ReadRequest(
        val scope: TimelineScope,
        val head: ConfirmedTimelineSnapshotHeadMetadata,
        val startedAtMillis: Long,
    ) {
        fun activeManifest(manifestId: String) = manifest(manifestId, RevisionPolicy.EXACT)
        fun fallbackManifest(manifestId: String) = manifest(manifestId, RevisionPolicy.AT_OR_BELOW)

        fun reconciliation(failure: SnapshotReadFailure) = ConfirmedTimelineReadResult.ReconciliationRequired(
            failure = failure,
            highWaterRevision = head.highWaterRevision,
        )

        private fun manifest(manifestId: String, revisionPolicy: RevisionPolicy) = ManifestRequest(
            scope = scope,
            manifestId = manifestId,
            maximumRevision = head.highWaterRevision,
            revisionPolicy = revisionPolicy,
        )
    }

    private data class ManifestRequest(
        val scope: TimelineScope,
        val manifestId: String,
        val maximumRevision: Long,
        val revisionPolicy: RevisionPolicy,
    )

    private enum class RevisionPolicy {
        EXACT,
        AT_OR_BELOW;

        fun accepts(revision: Long, maximumRevision: Long): Boolean = when (this) {
            EXACT -> revision == maximumRevision
            AT_OR_BELOW -> revision <= maximumRevision
        }
    }

    private enum class ReadSource(val telemetryEvent: String) {
        ACTIVE("readSnapshot.success"),
        FALLBACK("readSnapshot.fallback"),
    }

    private data class ReadObservation(
        val request: ReadRequest,
        val read: ManifestRead.Valid,
        val source: ReadSource,
    )

    private sealed interface ManifestRead {
        data class Valid(val envelope: StoredTimelineEnvelope, val byteLength: Long) : ManifestRead
        data class Invalid(val failure: SnapshotReadFailure) : ManifestRead
    }

    private sealed interface ManifestPayload {
        data class Valid(val bytes: ByteArray) : ManifestPayload
        data class Invalid(val failure: SnapshotReadFailure) : ManifestPayload
    }

    private object ManifestValidator {
        fun validateMetadata(
            manifest: ConfirmedTimelineSnapshotManifestEntity,
            request: ManifestRequest,
        ): SnapshotReadFailure? =
            validateScope(manifest, request.scope)
                ?: validateRevision(manifest, request)
                ?: validateSchema(manifest)
                ?: validateShape(manifest)

        fun validateChunk(
            manifest: ConfirmedTimelineSnapshotManifestEntity,
            index: Int,
            chunk: ByteArray,
        ): SnapshotReadFailure? = SnapshotReadFailure.CHUNK_INVALID.takeIf {
            chunk.size != manifest.expectedChunkSize(index) || chunk.size > CHUNK_SIZE_BYTES
        }

        fun validatePayload(
            manifest: ConfirmedTimelineSnapshotManifestEntity,
            bytes: ByteArray,
            checksum: ByteArray,
        ): ManifestPayload {
            if (bytes.size.toLong() != manifest.byteLength) {
                return ManifestPayload.Invalid(SnapshotReadFailure.LENGTH_MISMATCH)
            }
            if (checksum.toHex() != manifest.sha256.lowercase()) {
                return ManifestPayload.Invalid(SnapshotReadFailure.CHECKSUM_MISMATCH)
            }
            return ManifestPayload.Valid(bytes)
        }

        fun validateEnvelope(
            scope: TimelineScope,
            manifest: ConfirmedTimelineSnapshotManifestEntity,
            envelope: StoredTimelineEnvelope,
        ): SnapshotReadFailure? =
            SnapshotReadFailure.SCOPE_MISMATCH.takeIf { envelope.scope != scope }
                ?: SnapshotReadFailure.REVISION_MISMATCH.takeIf { envelope.revision != manifest.revision }
                ?: SnapshotReadFailure.SCHEMA_MISMATCH.takeIf { envelope.schemaVersion != manifest.schemaVersion }

        private fun validateScope(
            manifest: ConfirmedTimelineSnapshotManifestEntity,
            scope: TimelineScope,
        ): SnapshotReadFailure? = SnapshotReadFailure.SCOPE_MISMATCH.takeUnless { manifest.matches(scope) }

        private fun validateRevision(
            manifest: ConfirmedTimelineSnapshotManifestEntity,
            request: ManifestRequest,
        ): SnapshotReadFailure? = SnapshotReadFailure.REVISION_MISMATCH.takeIf {
            manifest.revision < 0L || !request.revisionPolicy.accepts(manifest.revision, request.maximumRevision)
        }

        private fun validateSchema(manifest: ConfirmedTimelineSnapshotManifestEntity): SnapshotReadFailure? =
            SnapshotReadFailure.SCHEMA_MISMATCH.takeUnless {
                manifest.schemaVersion in 1..StoredTimelineEnvelope.CURRENT_SCHEMA_VERSION
            }

        private fun validateShape(manifest: ConfirmedTimelineSnapshotManifestEntity): SnapshotReadFailure? =
            SnapshotReadFailure.METADATA_INVALID.takeUnless { manifest.hasBoundedShape() }
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

private fun ConfirmedTimelineSnapshotManifestEntity.hasBoundedShape(): Boolean =
    hasBoundedByteLength() && hasExpectedChunkCount() && hasSha256Checksum()

private fun ConfirmedTimelineSnapshotManifestEntity.hasBoundedByteLength(): Boolean =
    byteLength in 1..RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES.toLong() * MAX_SNAPSHOT_CHUNKS

private fun ConfirmedTimelineSnapshotManifestEntity.hasExpectedChunkCount(): Boolean {
    val expectedCount = ((byteLength + RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES - 1) /
        RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES).toInt()
    return chunkCount == expectedCount && chunkCount in 1..MAX_SNAPSHOT_CHUNKS
}

private fun ConfirmedTimelineSnapshotManifestEntity.hasSha256Checksum(): Boolean =
    sha256.length == SHA_256_HEX_LENGTH && sha256.all(Char::isHexDigit)

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

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

private const val MAX_SNAPSHOT_CHUNKS = 2048
private const val SHA_256_HEX_LENGTH = 64
