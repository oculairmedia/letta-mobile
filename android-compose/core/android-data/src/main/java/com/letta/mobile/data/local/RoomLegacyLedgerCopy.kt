package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Source implementation must hold a legacy Room read transaction for the entire callback. */
interface LegacyLedgerCopySource {
    suspend fun <T> snapshot(scope: TimelineScope, block: suspend LegacyLedgerCopyReader.() -> T): T
}

interface LegacyLedgerCopyReader {
    /** Includes generation, revision, owner, schema, row count and exact envelope/row digests. */
    suspend fun head(): LegacyLedgerCopyHead
    suspend fun metadata(afterOrder: Long, maxRows: Int): List<LegacyLedgerCopyRow>
    suspend fun chunk(row: LegacyLedgerCopyRow, offset: Long, maxBytes: Int): ByteArray
}

data class LegacyLedgerCopyHead(
    val token: String,
    val rowCount: Long,
    val supported: Boolean,
    val kind: LegacyLedgerCopyKind = LegacyLedgerCopyKind.Normalized,
)

enum class LegacyLedgerCopyKind { Empty, Normalized, ManifestOnly }

/** Totals for one readiness attempt. Envelope decode must stay 0: readiness never reconstructs a v13 snapshot. */
data class CanonicalReadinessMeasurement(
    val copyRows: Int = 0,
    val copyBytes: Int = 0,
    val convertRows: Long = 0,
    val validateRows: Int = 0,
    val validateBytes: Int = 0,
    val envelopeDecodes: Int = 0,
    val copySteps: Int = 0,
    val convertSteps: Int = 0,
    val validateSteps: Int = 0,
)
data class LegacyLedgerCopyRow(val order: Long, val primary: Long, val secondary: Long, val bytes: Long, val checksum: String)

sealed interface LegacyLedgerCopyResult {
    data class Progress(val rows: Int, val bytes: Int, val complete: Boolean) : LegacyLedgerCopyResult
    data object SourceChanged : LegacyLedgerCopyResult
    data object SchemaMismatch : LegacyLedgerCopyResult
}

/**
 * Bounded post-open raw copy, never an active canonical generation. Both the source and target stay
 * intact. Activation requires shared canonical conversion/evidence and a cross-file source fence.
 * One pass copies <=64KiB and <=1 metadata row, including multi-megabyte legacy rows.
 */
class RoomLegacyLedgerCopy(
    private val source: LegacyLedgerCopySource,
    private val target: TimelineLedgerDatabase,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun step(scope: TimelineScope): LegacyLedgerCopyResult = withContext(io) {
        source.snapshot(scope) {
            val sourceHead = head()
            if (!sourceHead.supported) return@snapshot LegacyLedgerCopyResult.SchemaMismatch
            require(sourceHead.rowCount >= 0)
            target.withTransaction {
                val dao = target.ledger()
                val key = ledgerScopeKey(scope)
                val state = dao.migration(key) ?: LedgerMigrationState(
                    key, sourceHead.token, UUID.randomUUID().toString(), Long.MIN_VALUE, 0, false,
                )
                if (state.sourceToken != sourceHead.token) return@withTransaction LegacyLedgerCopyResult.SourceChanged
                if (state.complete) return@withTransaction LegacyLedgerCopyResult.Progress(0, 0, true)
                val rows = metadata(state.afterOrder, 1)
                check(rows.size <= 1) { "Unbounded migration source" }
                val row = rows.singleOrNull()
                if (row == null) {
                    check(state.pendingPointer == null && state.copiedRows == sourceHead.rowCount) { "Source count mismatch" }
                    dao.migration(state.copy(complete = true))
                    return@withTransaction LegacyLedgerCopyResult.Progress(0, 0, true)
                }
                check(row.order > state.afterOrder && row.bytes >= 0)
                check(state.pendingOffset in 0..row.bytes)
                val digest = ResumableLedgerSha256.restore(state.pendingDigest)
                val pointer = state.pendingPointer ?: UUID.randomUUID().toString()
                val bytes = chunk(row, state.pendingOffset, RoomTimelineBoundedStore.CHUNK_BYTES)
                check(bytes.size.toLong() == minOf(RoomTimelineBoundedStore.CHUNK_BYTES.toLong(), row.bytes - state.pendingOffset)) { "Short source body" }
                digest.update(bytes)
                if (bytes.isNotEmpty()) {
                    dao.chunk(LedgerChunk(key, pointer, state.pendingOffset / RoomTimelineBoundedStore.CHUNK_BYTES, bytes.copyOf(), checksum(bytes)))
                }
                val offset = state.pendingOffset + bytes.size
                if (offset == row.bytes) {
                    val exact = digest.finish()
                    check(exact.equals(row.checksum, ignoreCase = true)) { "Legacy body checksum mismatch" }
                    dao.blob(LedgerBlob(key, pointer, row.bytes, exact))
                    dao.migrationRow(LedgerMigrationRow(key, state.generation, row.order, row.primary, row.secondary, pointer, row.bytes, exact))
                    dao.migration(state.copy(afterOrder = row.order, copiedRows = state.copiedRows + 1, pendingPointer = null, pendingOffset = 0, pendingDigest = ""))
                    LegacyLedgerCopyResult.Progress(1, bytes.size, false)
                } else {
                    dao.migration(state.copy(pendingPointer = pointer, pendingOffset = offset, pendingDigest = digest.checkpoint()))
                    LegacyLedgerCopyResult.Progress(0, bytes.size, false)
                }
            }
        }
    }
}

/** SHA-256 chaining state at a block boundary lets a killed process resume without rereading a body. */
internal class ResumableLedgerSha256 private constructor(private val h: IntArray, private var count: Long) {
    private var remainder = ByteArray(0)

    fun update(bytes: ByteArray) {
        count = Math.addExact(count, bytes.size.toLong())
        val input = remainder + bytes
        var offset = 0
        while (offset + 64 <= input.size) { compress(input, offset); offset += 64 }
        remainder = input.copyOfRange(offset, input.size)
    }

    fun checkpoint(): String = (listOf(count.toString()) + h.map { it.toUInt().toString(16) } +
        listOf(remainder.joinToString("") { "%02x".format(it.toInt() and 255) })).joinToString(":")

    fun finish(): String {
        val padding = ByteArray(if (remainder.size < 56) 64 else 128)
        remainder.copyInto(padding)
        padding[remainder.size] = 0x80.toByte()
        val bits = Math.multiplyExact(count, 8L)
        for (i in 0..7) padding[padding.size - 1 - i] = (bits ushr (i * 8)).toByte()
        compress(padding, 0)
        if (padding.size == 128) compress(padding, 64)
        return h.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }

    private fun compress(bytes: ByteArray, offset: Int) {
        val w = IntArray(64)
        for (i in 0..15) for (j in 0..3) w[i] = (w[i] shl 8) or (bytes[offset + i * 4 + j].toInt() and 255)
        for (i in 16..63) {
            val x = w[i - 15]; val y = w[i - 2]
            val s0 = x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)
            val s1 = y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var z = h[7]
        for (i in 0..63) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val t1 = z + s1 + ((e and f) xor (e.inv() and g)) + K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val t2 = s0 + ((a and b) xor (a and c) xor (b and c))
            z = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        val values = intArrayOf(a, b, c, d, e, f, g, z)
        for (i in h.indices) h[i] += values[i]
    }

    companion object {
        fun restore(state: String): ResumableLedgerSha256 {
            if (state.isEmpty()) return ResumableLedgerSha256(intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(), 0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19), 0)
            val parts = state.split(':')
            require(parts.size == 9 || parts.size == 10)
            val count = parts[0].toLong()
            val tail = if (parts.size == 10) parts[9] else ""
            require(tail.length % 2 == 0 && tail.length < 128)
            val bytes = tail.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            require(count >= 0 && count % 64 == bytes.size.toLong())
            return ResumableLedgerSha256(IntArray(8) { parts[it + 1].toUInt(16).toInt() }, count).also { it.remainder = bytes }
        }
        private val K = longArrayOf(
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2,
        ).map { it.toInt() }.toIntArray()
    }
}
