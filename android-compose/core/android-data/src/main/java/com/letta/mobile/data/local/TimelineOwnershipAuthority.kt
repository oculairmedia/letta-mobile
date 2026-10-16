package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class TimelineOwnershipBusyException : IllegalStateException("Timeline ownership busy; retry outside locks")

/** Retry only lock acquisition failures, after the failed attempt has released its locks. */
suspend fun <T> retryTimelineOwnership(block: suspend () -> T): T {
    repeat(100) { attempt ->
        try {
            return block()
        } catch (busy: TimelineOwnershipBusyException) {
            if (attempt == 99) throw busy
        }
        kotlinx.coroutines.delay(25)
    }
    error("Unreachable ownership retry")
}

/**
 * Per-conversation owner record for the canonical timeline ledger (letta-mobile-by4vm.15: the only
 * Android timeline). A record pins the agent that first claimed a conversation and the epoch every
 * lease is checked against; a backend OS lock spans each guarded callback, including its database
 * commit. Contention fails fast: callers retry outside database/coordinator locks.
 *
 * Records written by older builds may still carry the retired migration phases (Legacy, Migrating,
 * Prepared) and a source mapping; they decode, and [claimCanonical] moves them to Canonical without
 * copying anything. State is checksummed, bounded and atomically replaced then directory-fsynced.
 * Corruption and IO errors fail closed.
 */
class TimelineOwnershipAuthority internal constructor(
    private val directory: Path,
    private val syncDirectory: (Path) -> Unit,
) {
    constructor(directory: Path) : this(directory, ::forceDirectory)

    /** Legacy, Migrating and Prepared survive only as decodable phases of records older builds wrote. */
    enum class Phase { Legacy, Migrating, Prepared, Canonical }
    enum class Route { Canonical }
    data class Receipt(val sourceToken: String, val targetGeneration: String, val targetRevision: Long, val certificateId: String = "")
    data class Mapping(val source: TimelineScope, val sourceEpoch: Long, val target: TimelineScope, val targetEpoch: Long)
    data class State(val scope: TimelineScope, val epoch: Long, val phase: Phase, val receipt: Receipt? = null, val mapping: Mapping? = null)
    data class Lease(val scope: TimelineScope, val epoch: Long, val route: Route)

    suspend fun state(scope: TimelineScope): State = locked(scope.backendId) { read(scope) }

    /**
     * Read-only owner lookup that tolerates an unclaimed conversation. A conversation opened for
     * the first time in a backend that already manages others has no record yet, which is ordinary
     * traffic, not the lost record [read] demands recovery for. Returns null when nothing has
     * claimed the conversation.
     */
    suspend fun ownerAgent(scope: TimelineScope): String? = locked(scope.backendId) {
        if (Files.exists(statePath(scope))) read(scope).scope.agentId else null
    }

    /**
     * letta-mobile-by4vm.15: returns the canonical lease for [scope], publishing the Canonical phase
     * first when the scope has none (unclaimed, Legacy, or an interrupted migration). No history is
     * copied: the ledger is the only timeline and pages anything it lacks from the server.
     */
    suspend fun claimCanonical(scope: TimelineScope): Lease = locked(scope.backendId) { claimLocked(scope) }

    private suspend fun claimLocked(scope: TimelineScope): Lease {
        val state = if (Files.exists(statePath(scope))) read(scope) else State(scope, 0, Phase.Legacy)
        check(state.scope == scope) { "Timeline ownership scope mismatch: stored=${state.scope} requested=$scope" }
        if (state.phase == Phase.Canonical) return Lease(scope, state.epoch, Route.Canonical)
        val next = State(scope, Math.addExact(state.epoch, 1), Phase.Canonical, CLAIMED_RECEIPT)
        publish(next)
        return Lease(scope, next.epoch, Route.Canonical)
    }

    suspend fun <T> withLease(lease: Lease, block: suspend () -> T): T = locked(lease.scope.backendId) {
        val state = read(lease.scope)
        check(state.epoch == lease.epoch) { "Stale timeline ownership epoch" }
        requireRoute(state, lease.scope, lease.route)
        block()
    }

    private fun requireRoute(state: State, scope: TimelineScope, route: Route) {
        // The record is keyed by backend and conversation only, so agentId is the field that can
        // disagree here. Name both scopes: the bare message cannot be diagnosed from a crash log.
        check(state.scope == scope) {
            "Timeline ownership scope mismatch: stored=${state.scope} requested=$scope"
        }
        check(route == Route.Canonical && state.phase == Phase.Canonical) { "Timeline ownership route fenced: ${state.phase}" }
    }

    private suspend fun <T> locked(backendId: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val backend = backendDirectory(backendId)
        // Idempotent and cheap. Durability of the new entry is publish()'s job: a read-only
        // acquisition (every guarded read on chat open) must not pay a directory fsync, and a
        // lock or state read survives a crash that loses an empty, never-published directory.
        Files.createDirectories(backend)
        FileChannel.open(backend.resolve("ownership.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            (lock ?: throw TimelineOwnershipBusyException()).use {
                currentCoroutineContext().ensureActive()
                block()
            }
        }
    }

    private fun backendDirectory(backend: String): Path = directory.resolve(hash(encode { writeUTF(backend) }))
    private fun statePath(scope: TimelineScope): Path = backendDirectory(scope.backendId).resolve(hash(encode { writeUTF(scope.conversationId) }) + ".state")

    private fun read(scope: TimelineScope): State {
        val path = statePath(scope)
        if (!Files.exists(path)) {
            check(!Files.exists(backendDirectory(scope.backendId).resolve("managed"))) {
                "Missing ownership record in managed backend; explicit recovery required"
            }
            return State(scope, 0, Phase.Legacy)
        }
        val bytes = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val size = channel.size()
            check(size in 33..MAX_STATE.toLong()) { "Invalid ownership state size" }
            val buffer = java.nio.ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Truncated ownership state" }
            buffer.array()
        }
        val payload = bytes.copyOfRange(32, bytes.size)
        check(MessageDigest.isEqual(bytes.copyOfRange(0, 32), digest(payload))) { "Corrupt ownership state" }
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val version = input.readInt()
            check(version in 1..2) { "Unknown ownership format" }
            val stored = TimelineScope(input.readUTF(), input.readUTF(), if (input.readBoolean()) input.readUTF() else null)
            val epoch = input.readLong()
            val phase = Phase.valueOf(input.readUTF())
            val receipt = if (input.readBoolean()) Receipt(input.readUTF(), input.readUTF(), input.readLong()) else null
            check(stored.backendId == scope.backendId && stored.conversationId == scope.conversationId && epoch >= 0)
            val boundReceipt = if (receipt != null && input.available() > 0) receipt.copy(certificateId = input.readUTF()) else receipt
            val mapping = if (version == 2 && input.readBoolean()) {
                fun scope() = TimelineScope(input.readUTF(), input.readUTF(), if (input.readBoolean()) input.readUTF() else null)
                Mapping(scope(), input.readLong(), scope(), input.readLong()).also {
                    check(it.source.backendId != it.target.backendId && it.sourceEpoch > 0 && it.targetEpoch > 0)
                    check((stored == it.source && epoch == it.sourceEpoch) || (stored == it.target && epoch == it.targetEpoch))
                }
            } else null
            check(input.available() == 0)
            check(epoch != 0L || (phase == Phase.Legacy && mapping == null && boundReceipt == null))
            check((phase == Phase.Prepared || phase == Phase.Canonical) == (boundReceipt != null))
            State(stored, epoch, phase, boundReceipt, mapping)
        }
    }

    private suspend fun publish(state: State) {
        val payload = encode {
            writeInt(2); writeUTF(state.scope.backendId); writeUTF(state.scope.conversationId)
            writeBoolean(state.scope.agentId != null); state.scope.agentId?.let { writeUTF(it) }
            writeLong(state.epoch); writeUTF(state.phase.name); writeBoolean(state.receipt != null)
            state.receipt?.let { writeUTF(it.sourceToken); writeUTF(it.targetGeneration); writeLong(it.targetRevision); writeUTF(it.certificateId) }
            writeBoolean(state.mapping != null)
            state.mapping?.let {
                fun scope(value: TimelineScope) {
                    writeUTF(value.backendId); writeUTF(value.conversationId)
                    writeBoolean(value.agentId != null); value.agentId?.let { agent -> writeUTF(agent) }
                }
                scope(it.source); writeLong(it.sourceEpoch); scope(it.target); writeLong(it.targetEpoch)
            }
        }
        val bytes = digest(payload) + payload
        require(bytes.size <= MAX_STATE)
        currentCoroutineContext().ensureActive()
        // Once publication starts, finish its fsync even if cancellation arrives. A caller that loses
        // the result must reread state; it must never infer rollback from cancellation/IO failure.
        withContext(NonCancellable) {
            val backend = backendDirectory(state.scope.backendId)
            // Persist the backend directory's own entry before anything inside it is published.
            syncDirectory(directory)
            FileChannel.open(backend.resolve("managed"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { it.force(true) }
            syncDirectory(backend)
            val temporary = backend.resolve("next-state")
            FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, statePath(state.scope), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(backend)
        }
    }

    private fun encode(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { it.block() }
    }.toByteArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hash(bytes: ByteArray) = digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object {
        const val MAX_STATE = 64 * 1024
        /** A claim certifies nothing it copied: there is no source, generation or revision. */
        val CLAIMED_RECEIPT = Receipt(sourceToken = "claimed", targetGeneration = "claimed", targetRevision = 0)
    }
}

private fun forceDirectory(path: Path) {
    FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
}
