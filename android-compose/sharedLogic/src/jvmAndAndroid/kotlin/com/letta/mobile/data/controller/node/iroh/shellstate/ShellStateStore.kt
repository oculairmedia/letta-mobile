package com.letta.mobile.data.controller.node.iroh.shellstate

import com.letta.mobile.data.hoststate.HostStateFileLocks
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStores
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSchema
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateChange
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the document is kept. The stored state is exactly the wire's [ShellStateSnapshot] (schema
 * version, document revision and entries), so a host state export can carry it unchanged.
 */
internal interface ShellStatePersistence {
    /** The stored document; empty at revision 0 when nothing was ever stored. */
    fun read(): ShellStateSnapshot

    /** Replaces the stored document. */
    fun write(state: ShellStateSnapshot)
}

/** The document in memory: tests, and hosts without a state directory. */
internal class InMemoryShellStatePersistence(private var state: ShellStateSnapshot = ShellStateStore.EMPTY) : ShellStatePersistence {
    override fun read(): ShellStateSnapshot = state

    override fun write(state: ShellStateSnapshot) {
        this.state = state
    }
}

/**
 * The document in one JSON file that only the host's user may read, replaced by a rename. A missing file
 * is an empty document at revision 0. A file that does not decode, or that was written by a newer schema
 * than this build reads, is left as it is and every request is refused with `state_unreadable` (logged),
 * never silently reset: a reset would issue revisions a client already holds a second time.
 */
internal class FileShellStatePersistence(private val path: Path) : ShellStatePersistence {
    override fun read(): ShellStateSnapshot {
        if (!Files.exists(path)) return ShellStateStore.EMPTY
        val stored = try {
            LettaJson.persisted.decodeFromString(ShellStateSnapshot.serializer(), String(Files.readAllBytes(path), Charsets.UTF_8))
        } catch (expectedCorruption: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException: a truncated or hand-edited file.
            Telemetry.event(TAG, "state.unreadable", "class" attr expectedCorruption::class.simpleName, level = Telemetry.Level.WARN)
            throw ShellStateRefusal(ShellStateWire.STATE_UNREADABLE, expectedCorruption)
        }
        if (stored.schemaVersion > ShellStateSchema.VERSION) {
            Telemetry.event(TAG, "state.schema_newer", "schema" attr stored.schemaVersion, level = Telemetry.Level.WARN)
            throw ShellStateRefusal(ShellStateWire.STATE_UNREADABLE)
        }
        return stored
    }

    override fun write(state: ShellStateSnapshot) =
        IrohSecretKeyStores.writeOwnerOnly(path, LettaJson.persisted.encodeToString(ShellStateSnapshot.serializer(), state))

    private companion object {
        const val TAG = "ShellStateHost"
    }
}

/** A refusal with one of the contract's codes. */
internal class ShellStateRefusal(val code: String, cause: Throwable? = null) : Exception(code, cause)

/** What a write did: the document now, and whether it changed (and so must be pushed). */
internal class ShellStateWrite(val snapshot: ShellStateSnapshot, val changed: Boolean)

/**
 * The host's one shell state document. Writes are serialized, so every revision is issued exactly once.
 * The document revision goes up by one per accepted proposal that changed something, and every entry
 * that proposal wrote takes that revision, so an entry's revision never repeats, even when it is
 * removed and set again. Stores over one file must share [lock] (see [HostStateFileLocks]), or two of them
 * could accept proposals against the same revision and one would overwrite the other.
 */
internal class ShellStateStore(
    private val persistence: ShellStatePersistence,
    private val lock: Mutex = Mutex(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The document now. */
    suspend fun snapshot(): ShellStateSnapshot = lock.withLock { persistence.read() }

    /**
     * Applies all of [changes] on behalf of [actor], or none: refuses with a revision conflict when any
     * change's expected revision is not that entry's current one, and with `too_large` when the result
     * would pass a size limit. Changes that leave an entry as it is write nothing.
     */
    suspend fun propose(changes: List<ShellStateChange>, actor: ShellStateActor): ShellStateWrite = lock.withLock {
        val stored = persistence.read()
        requireCurrent(stored, changes)
        val effective = changes.filter { stored.entries[it.key]?.value != it.value }
        if (effective.isEmpty()) return@withLock ShellStateWrite(stored, changed = false)
        val next = apply(stored, effective, actor)
        requireWithinLimits(next)
        persistence.write(next)
        ShellStateWrite(next, changed = true)
    }

    /** Refuses with a revision conflict unless every change was based on its entry's current revision. */
    private fun requireCurrent(stored: ShellStateSnapshot, changes: List<ShellStateChange>) {
        if (changes.any { currentRevision(stored, it.key) != it.expectedRevision }) {
            throw ShellStateRefusal(ShellStateWire.REVISION_CONFLICT)
        }
    }

    private fun currentRevision(stored: ShellStateSnapshot, key: String): Long = stored.entries[key]?.revision ?: 0L

    /** The document after [changes]: the revision goes up once and every written entry takes it. */
    private fun apply(stored: ShellStateSnapshot, changes: List<ShellStateChange>, actor: ShellStateActor): ShellStateSnapshot {
        val revision = stored.documentRevision + 1
        val writtenAt = clock()
        val entries = stored.entries.toSortedMap()
        changes.forEach { change ->
            val value = change.value
            if (value == null) entries.remove(change.key) else entries[change.key] = ShellStateEntry(value, revision, actor, writtenAt)
        }
        return ShellStateSnapshot(ShellStateSchema.VERSION, revision, entries)
    }

    private fun requireWithinLimits(next: ShellStateSnapshot) {
        val tooMany = next.entries.size > ShellStateWire.MAX_ENTRIES
        if (tooMany || encodedBytes(next) > ShellStateWire.MAX_DOCUMENT_BYTES) throw ShellStateRefusal(ShellStateWire.TOO_LARGE)
    }

    private fun encodedBytes(state: ShellStateSnapshot): Int =
        LettaJson.persisted.encodeToString(ShellStateSnapshot.serializer(), state).toByteArray(Charsets.UTF_8).size

    internal companion object {
        /** The document of a host that never stored one. */
        val EMPTY = ShellStateSnapshot(ShellStateSchema.VERSION, documentRevision = 0, entries = emptyMap())
    }
}
