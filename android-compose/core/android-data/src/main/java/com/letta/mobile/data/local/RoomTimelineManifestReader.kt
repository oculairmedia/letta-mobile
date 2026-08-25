package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Performs bounded, cancellation-aware manifest reads and typed validation. */
internal class RoomTimelineManifestReader(
    private val dao: ConfirmedTimelineSnapshotDao,
) {
    suspend fun read(request: RoomManifestRequest): RoomManifestRead {
        val manifest = dao.getManifest(request.manifestId)
            ?: return RoomManifestRead.Invalid(SnapshotReadFailure.MANIFEST_MISSING)
        RoomManifestValidator.validateMetadata(manifest, request)?.let {
            return RoomManifestRead.Invalid(it)
        }
        return when (val payload = readPayload(manifest)) {
            is RoomManifestPayload.Valid -> decode(request.scope, manifest, payload.bytes)
            is RoomManifestPayload.Invalid -> RoomManifestRead.Invalid(payload.failure)
        }
    }

    private suspend fun readPayload(manifest: ConfirmedTimelineSnapshotManifestEntity): RoomManifestPayload {
        val output = ByteArrayOutputStream(manifest.byteLength.toInt())
        val digest = MessageDigest.getInstance(SHA_256)
        repeat(manifest.chunkCount) { index ->
            coroutineContext.ensureActive()
            val chunk = dao.getChunk(manifest.manifestId, index)
                ?: return RoomManifestPayload.Invalid(SnapshotReadFailure.CHUNK_MISSING)
            RoomManifestValidator.validateChunk(manifest, RoomChunk(index, chunk))?.let {
                return RoomManifestPayload.Invalid(it)
            }
            digest.update(chunk)
            output.write(chunk)
        }
        return RoomManifestValidator.validatePayload(manifest, output.toByteArray(), digest.digest())
    }

    private fun decode(
        scope: TimelineScope,
        manifest: ConfirmedTimelineSnapshotManifestEntity,
        bytes: ByteArray,
    ): RoomManifestRead {
        val payload = bytes.decodeUtf8Strict()
            ?: return RoomManifestRead.Invalid(SnapshotReadFailure.CORRUPT_ENCODING)
        val envelope = TimelineSnapshotCodec.decode(payload)
            ?: return RoomManifestRead.Invalid(SnapshotReadFailure.CORRUPT_ENCODING)
        RoomManifestValidator.validateEnvelope(scope, manifest, envelope)?.let {
            return RoomManifestRead.Invalid(it)
        }
        return RoomManifestRead.Valid(envelope, manifest.byteLength)
    }
}

internal data class RoomSnapshotReadRequest(
    val scope: TimelineScope,
    val head: ConfirmedTimelineSnapshotHeadMetadata,
    val startedAtMillis: Long,
) {
    fun activeManifest(manifestId: String) = manifest(manifestId, RoomRevisionPolicy.EXACT)
    fun fallbackManifest(manifestId: String) = manifest(manifestId, RoomRevisionPolicy.AT_OR_BELOW)

    fun reconciliation(failure: SnapshotReadFailure) =
        com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult.ReconciliationRequired(
            failure = failure,
            highWaterRevision = head.highWaterRevision,
        )

    private fun manifest(manifestId: String, policy: RoomRevisionPolicy) = RoomManifestRequest(
        scope = scope,
        manifestId = manifestId,
        maximumRevision = head.highWaterRevision,
        revisionPolicy = policy,
    )
}

internal enum class RoomReadSource(val telemetryEvent: String) {
    ACTIVE("readSnapshot.success"),
    FALLBACK("readSnapshot.fallback"),
}

internal data class RoomReadObservation(
    val request: RoomSnapshotReadRequest,
    val read: RoomManifestRead.Valid,
    val source: RoomReadSource,
)

internal data class RoomManifestRequest(
    val scope: TimelineScope,
    val manifestId: String,
    val maximumRevision: Long,
    val revisionPolicy: RoomRevisionPolicy,
)

internal enum class RoomRevisionPolicy {
    EXACT,
    AT_OR_BELOW;

    fun accepts(revision: Long, maximumRevision: Long): Boolean = when (this) {
        EXACT -> revision == maximumRevision
        AT_OR_BELOW -> revision <= maximumRevision
    }
}

internal sealed interface RoomManifestRead {
    data class Valid(val envelope: StoredTimelineEnvelope, val byteLength: Long) : RoomManifestRead
    data class Invalid(val failure: SnapshotReadFailure) : RoomManifestRead
}

private sealed interface RoomManifestPayload {
    data class Valid(val bytes: ByteArray) : RoomManifestPayload
    data class Invalid(val failure: SnapshotReadFailure) : RoomManifestPayload
}

private data class RoomChunk(val index: Int, val payload: ByteArray)

private object RoomManifestValidator {
    fun validateMetadata(
        manifest: ConfirmedTimelineSnapshotManifestEntity,
        request: RoomManifestRequest,
    ): SnapshotReadFailure? =
        validateScope(manifest, request.scope)
            ?: validateRevision(manifest, request)
            ?: validateSchema(manifest)
            ?: validateShape(manifest)

    fun validateChunk(
        manifest: ConfirmedTimelineSnapshotManifestEntity,
        chunk: RoomChunk,
    ): SnapshotReadFailure? = SnapshotReadFailure.CHUNK_INVALID.takeIf {
        chunk.payload.size != manifest.expectedChunkSize(chunk.index) ||
            chunk.payload.size > RoomConfirmedTimelineStore.CHUNK_SIZE_BYTES
    }

    fun validatePayload(
        manifest: ConfirmedTimelineSnapshotManifestEntity,
        bytes: ByteArray,
        checksum: ByteArray,
    ): RoomManifestPayload {
        if (bytes.size.toLong() != manifest.byteLength) {
            return RoomManifestPayload.Invalid(SnapshotReadFailure.LENGTH_MISMATCH)
        }
        if (checksum.toHex() != manifest.sha256.lowercase()) {
            return RoomManifestPayload.Invalid(SnapshotReadFailure.CHECKSUM_MISMATCH)
        }
        return RoomManifestPayload.Valid(bytes)
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
        request: RoomManifestRequest,
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

internal fun ConfirmedTimelineSnapshotHeadMetadata.matches(scope: TimelineScope): Boolean =
    backendId == scope.backendId && conversationId == scope.conversationId && agentId == scope.agentId &&
        highWaterRevision >= 0L

internal fun ConfirmedTimelineSnapshotManifestEntity.matches(scope: TimelineScope): Boolean =
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

internal fun ByteArray.asTimelineChunks(manifestId: String): List<ConfirmedTimelineSnapshotChunkEntity> =
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

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance(SHA_256).digest(bytes).toHex()

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val MAX_SNAPSHOT_CHUNKS = 2048
private const val SHA_256_HEX_LENGTH = 64
private const val SHA_256 = "SHA-256"
