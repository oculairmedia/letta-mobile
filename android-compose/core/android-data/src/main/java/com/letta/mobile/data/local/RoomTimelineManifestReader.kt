package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.TimelineRevision
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
            currentCoroutineContext().ensureActive()
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

    fun reconciliation(failure: SnapshotReadFailure): ConfirmedTimelineReadResult.ReconciliationRequired =
        ConfirmedTimelineReadResult.ReconciliationRequired(
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
    class Valid(val bytes: ByteArray) : RoomManifestPayload
    data class Invalid(val failure: SnapshotReadFailure) : RoomManifestPayload
}

private class RoomChunk(val index: Int, val payload: ByteArray)

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

/**
 * letta-mobile-827s9.4, review round 2 item 2: NORMALIZED head ownership.
 *
 * The normalized CAS previously compared revision only, so a different agent sharing a
 * backend and conversation id could commit whenever its base revision happened to match --
 * transferring ownership (the head's agent_id is written from the committing scope) or
 * leaving a digest computed under one identity validated under another.
 *
 * Same rule as the legacy head guard and as `TimelineRepository.canAlias`'s intent: an
 * unowned head may be adopted once, and thereafter only that agent may write it. A null
 * requesting scope is treated as compatible so unscoped callers and bootstrap paths keep
 * working, exactly as they do for reads.
 */
/**
 * The complete normalized CAS precondition, as one named predicate.
 *
 * Both commit branches previously inlined the same conditional (revision matches, owner
 * matches), which CodeScene flagged as complex and which made it easy for the branches to
 * drift -- they had in fact already drifted once on the ownership half.
 *
 * A NULL head satisfies this at baseRevision 0: that is the bootstrap commit, which has no
 * predecessor to compare against. NoOp additionally requires a head to be present -- there is
 * nothing to no-op otherwise -- so it keeps that check at its call site rather than folding it
 * in here and silently breaking bootstrap.
 */
internal fun NormalizedTimelineSnapshotHeadEntity?.acceptsCommitAt(
    baseRevision: TimelineRevision,
    scope: TimelineScope,
): Boolean = (this?.revision ?: 0L) == baseRevision.value && ownedBy(scope)

internal fun NormalizedTimelineSnapshotHeadEntity?.ownedBy(scope: TimelineScope): Boolean {
    val owner = this?.agentId ?: return true
    // Round 4: the `scope.agentId == null` leg was a hole, and I put it there deliberately
    // "so unscoped callers keep working". It let an UNSCOPED writer mutate an OWNED head:
    // Apply writes agentId = scope.agentId, clearing ownership outright, and NoOp recomputes
    // the root under a null scope against an owned head, so the owner's later reads fail
    // checksum. Adoption is legitimate only from an UNOWNED head (handled above); once a head
    // has an owner, only that exact owner may write it.
    //
    // This now matches ownershipCompatibleWith for the legacy head, which already refused the
    // scoped -> unscoped downgrade for the same reason. The two guards were inconsistent.
    return owner == scope.agentId
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

/**
 * Legacy-head ownership: an UNOWNED head may be adopted, an owned one only by its owner.
 *
 * A null requesting agent is deliberately NOT allowed to take over an owned head -- that is the
 * same asymmetry [ownedBy] enforces for the normalized head.
 */
internal fun ConfirmedTimelineSnapshotHeadMetadata.ownershipCompatibleWith(scope: TimelineScope): Boolean =
    agentId == null || agentId == scope.agentId
