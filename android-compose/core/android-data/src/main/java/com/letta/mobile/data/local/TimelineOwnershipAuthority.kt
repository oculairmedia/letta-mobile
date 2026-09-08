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

/**
 * Dormant storage primitive, not an activation service. All participants must use the same directory.
 * A backend OS lock spans the callback, including database commit, so processes cannot race a phase
 * change against a guarded write. Backend-wide maintenance shares that lock. Contention fails fast:
 * callers retry outside database/coordinator locks. No waiting while holding a Room transaction.
 *
 * Lock order: authority -> legacy Room -> ledger Room. Never reenter this authority from a callback.
 * In particular the legacy checkpoint must use an already-guarded private write implementation.
 * Old binaries/direct DAOs do not honor this protocol; rollout must exclude them before activation.
 * State is checksummed, bounded and atomically replaced then directory-fsynced. Corruption and IO
 * errors fail closed. A phase change is not a cross-database transaction: Prepared records are only
 * published after the target commit, Canonical only after validation while the authority is held.
 */
class TimelineOwnershipAuthority(private val directory: Path) {
    enum class Phase { Legacy, Migrating, Prepared, Canonical }
    enum class Route { Legacy, Migration, Canonical }
    data class Receipt(val sourceToken: String, val targetGeneration: String, val targetRevision: Long)
    data class State(val scope: TimelineScope, val epoch: Long, val phase: Phase, val receipt: Receipt? = null)
    data class Lease(val scope: TimelineScope, val epoch: Long, val route: Route)

    suspend fun state(scope: TimelineScope): State = locked(scope.backendId) { read(scope) }

    /** Capture before admitting work; a lease is checked again while holding the storage lock. */
    suspend fun acquire(scope: TimelineScope, route: Route): Lease = locked(scope.backendId) {
        val state = read(scope)
        requireRoute(state, scope, route)
        Lease(scope, state.epoch, route)
    }

    suspend fun <T> withLease(lease: Lease, block: suspend () -> T): T = locked(lease.scope.backendId) {
        val state = read(lease.scope)
        check(state.epoch == lease.epoch) { "Stale timeline ownership epoch" }
        requireRoute(state, lease.scope, lease.route)
        block()
    }

    /** Runtime must close admission and drain accepted legacy writes BEFORE calling this. */
    suspend fun beginMigration(legacy: Lease): Lease = locked(legacy.scope.backendId) {
        check(legacy.route == Route.Legacy)
        val previous = read(legacy.scope)
        check(previous.scope == legacy.scope && previous.phase == Phase.Legacy && previous.epoch == legacy.epoch)
        val next = State(legacy.scope, Math.addExact(previous.epoch, 1), Phase.Migrating)
        publish(next)
        Lease(next.scope, next.epoch, Route.Migration)
    }

    /** Validation callback runs under the same authority as the publication, not before acquisition. */
    suspend fun prepare(migration: Lease, validate: suspend () -> Receipt): State = locked(migration.scope.backendId) {
        checkMigration(migration, Phase.Migrating)
        val receipt = validate()
        require(receipt.targetRevision >= 0 && receipt.sourceToken.isNotEmpty() && receipt.targetGeneration.isNotEmpty())
        State(migration.scope, migration.epoch, Phase.Prepared, receipt).also { publish(it) }
    }

    /** Does not select any runtime route. Caller must still keep activation_dormant until integrated. */
    suspend fun commitSwitch(migration: Lease, validate: suspend (Receipt) -> Unit): Lease = locked(migration.scope.backendId) {
        val previous = checkMigration(migration, Phase.Prepared)
        validate(checkNotNull(previous.receipt))
        publish(previous.copy(phase = Phase.Canonical))
        Lease(previous.scope, previous.epoch, Route.Canonical)
    }

    /** No Canonical -> Legacy transition: a frozen legacy snapshot cannot preserve later writes. */
    suspend fun abortBeforeSwitch(migration: Lease): Lease = locked(migration.scope.backendId) {
        check(migration.route == Route.Migration)
        val previous = read(migration.scope)
        check(previous.scope == migration.scope && previous.epoch == migration.epoch)
        check(previous.phase == Phase.Migrating || previous.phase == Phase.Prepared)
        val next = State(previous.scope, Math.addExact(previous.epoch, 1), Phase.Legacy)
        publish(next)
        Lease(next.scope, next.epoch, Route.Legacy)
    }

    /** Conservative backend maintenance: reject once any ownership record exists. No history scan. */
    suspend fun <T> legacyMaintenance(backendId: String, block: suspend () -> T): T = locked(backendId) {
        check(!Files.exists(backendDirectory(backendId).resolve("managed"))) { "Managed backend requires coordinated maintenance" }
        block()
    }

    private fun requireRoute(state: State, scope: TimelineScope, route: Route) {
        check(state.scope == scope) { "Timeline ownership scope mismatch" }
        check(when (route) {
            Route.Legacy -> state.phase == Phase.Legacy
            Route.Migration -> state.phase == Phase.Migrating
            Route.Canonical -> state.phase == Phase.Canonical
        }) { "Timeline ownership route fenced: ${state.phase}" }
    }

    private fun checkMigration(lease: Lease, phase: Phase): State {
        check(lease.route == Route.Migration)
        return read(lease.scope).also {
            check(it.scope == lease.scope && it.epoch == lease.epoch && it.phase == phase) { "Stale migration lease" }
        }
    }

    private suspend fun <T> locked(backendId: String, block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val backend = backendDirectory(backendId)
        Files.createDirectories(backend)
        // Persist newly created directory entries before any state publication.
        syncDirectory(directory)
        FileChannel.open(backend.resolve("ownership.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            checkNotNull(lock) { "Timeline ownership busy; retry outside locks" }.use {
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
            check(input.readInt() == 1) { "Unknown ownership format" }
            val stored = TimelineScope(input.readUTF(), input.readUTF(), if (input.readBoolean()) input.readUTF() else null)
            val epoch = input.readLong()
            val phase = Phase.valueOf(input.readUTF())
            val receipt = if (input.readBoolean()) Receipt(input.readUTF(), input.readUTF(), input.readLong()) else null
            check(stored.backendId == scope.backendId && stored.conversationId == scope.conversationId && epoch > 0)
            check(input.available() == 0)
            check((phase == Phase.Prepared || phase == Phase.Canonical) == (receipt != null))
            State(stored, epoch, phase, receipt)
        }
    }

    private suspend fun publish(state: State) {
        val payload = encode {
            writeInt(1); writeUTF(state.scope.backendId); writeUTF(state.scope.conversationId)
            writeBoolean(state.scope.agentId != null); state.scope.agentId?.let { writeUTF(it) }
            writeLong(state.epoch); writeUTF(state.phase.name); writeBoolean(state.receipt != null)
            state.receipt?.let { writeUTF(it.sourceToken); writeUTF(it.targetGeneration); writeLong(it.targetRevision) }
        }
        val bytes = digest(payload) + payload
        require(bytes.size <= MAX_STATE)
        currentCoroutineContext().ensureActive()
        // Once publication starts, finish its fsync even if cancellation arrives. A caller that loses
        // the result must reread state; it must never infer rollback from cancellation/IO failure.
        withContext(NonCancellable) {
            val backend = backendDirectory(state.scope.backendId)
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

    private fun syncDirectory(path: Path) { FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } }
    private fun encode(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { it.block() }
    }.toByteArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hash(bytes: ByteArray) = digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private companion object { const val MAX_STATE = 64 * 1024 }
}
