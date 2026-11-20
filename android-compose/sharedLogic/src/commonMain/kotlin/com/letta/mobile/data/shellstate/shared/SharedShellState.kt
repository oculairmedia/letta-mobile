package com.letta.mobile.data.shellstate.shared

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSchema
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateChange
import com.letta.mobile.data.shellstate.wire.ShellStateError
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.shellstate.wire.toJson
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What this device knows of the host's shell state. [snapshot] is the last document read from the host
 * while this repository lives (kept in memory across a failed read or a disconnect, never stored on the
 * device); [live] is whether it was read from the connected host since the connection came up. Edits
 * need [editable].
 */
data class ShellStateState(
    /** Whether the connected host serves the shell-state contract. */
    val supported: Boolean = false,
    /** The last document learned; null until one was. */
    val snapshot: ShellStateSnapshot? = null,
    /** Whether [snapshot] was read from the connected host on this connection. */
    val live: Boolean = false,
    /** The last request's failure; null after a success. */
    val error: ShellStateError? = null,
) {
    /** The entries as last learned; empty until a document was. */
    val entries: Map<String, ShellStateEntry> get() = snapshot?.entries.orEmpty()

    /** Whether an edit may be sent now. */
    val editable: Boolean get() = supported && live && snapshot != null
}

/** How one proposal ended. */
enum class ShellStateEditOutcome {
    /** The host accepted it (or it changed nothing). */
    APPLIED,

    /** Another writer changed one of the entries first; the document was read again. */
    CONFLICT,

    /** The host is out of reach or the document is not read yet; nothing was sent or queued. */
    OFFLINE,

    /** The host does not keep shared shell state. */
    UNSUPPORTED,

    /** The host refused the proposal (malformed, too large, unreadable store or not allowed). */
    REFUSED,
}

/**
 * The host's shell state document, the one copy every surface reads and edits.
 *
 * A proposal carries, for each entry it touches, the revision this device last saw of it, so it only
 * conflicts with another writer of the same entry; the host's answer replaces the shown document, a
 * conflict reads the document again, and nothing is ever queued. While the host is out of reach the last
 * document learned keeps showing and edits are refused ([ShellStateEditOutcome.OFFLINE]). Pushes from
 * the host replace the document when they are newer. A host without the contract has no shell state at
 * all, and no device-local copy stands in for it. Nothing here logs a key or a value; counts and
 * revisions only.
 */
class SharedShellState(
    private val link: ShellStateLink,
) {
    private val edits = Mutex()
    private val mutableState = MutableStateFlow(ShellStateState())

    /**
     * Whether the shown snapshot came from the connection that is up now. Revisions only order snapshots of one
     * host's one connection: a snapshot kept from an earlier connection (possibly another host, at any
     * revision) never outranks what the current connection says.
     */
    private var shownFromThisConnection = false

    /** Held across every change of the snapshot and of [shownFromThisConnection], so deciding and adopting are one step. */
    private val adopting = Mutex()

    /** What this device knows now. */
    val state: StateFlow<ShellStateState> = mutableState.asStateFlow()

    /** Whether the connected host serves the contract right now. */
    val supported: Boolean get() = ShellStateWire.CAPABILITY in link.hostCapabilities

    /** Follows the connection and the host's pushes until cancelled: reads again on every reconnect. */
    suspend fun run() = coroutineScope {
        link.pushes.onEach(::onPush).launchIn(this)
        link.connection.distinctUntilChanged().collect { up -> if (up) refresh() else connectionDown() }
    }

    /** Reads the host's document; a host without the contract leaves [state] unsupported. */
    suspend fun refresh(): ShellStateState {
        if (!supported) {
            // While disconnected the link advertises nothing, which says nothing about the host: keep the last
            // document for offline display. A connected host without the contract has no state to show, and
            // another host's document must not linger.
            if (link.isConnected) dropDocument() else markStale()
            return mutableState.value
        }
        mutableState.update { it.copy(supported = true) }
        when (val read = request(ShellStateWire.GET, JsonObject(emptyMap()))) {
            null -> markStale()
            else -> adopt(read, live = true)
        }
        return mutableState.value
    }

    private fun markStale() = mutableState.update { it.copy(live = false) }

    private suspend fun dropDocument() = adopting.withLock {
        shownFromThisConnection = false
        mutableState.update { it.copy(supported = false, live = false, snapshot = null) }
    }

    private suspend fun connectionDown() = adopting.withLock {
        shownFromThisConnection = false
        markStale()
    }

    /** Sets [key] to [value]. */
    suspend fun set(key: String, value: JsonElement): ShellStateEditOutcome = propose(listOf(Edit(key, value)))

    /** Removes [key]. */
    suspend fun remove(key: String): ShellStateEditOutcome = propose(listOf(Edit(key, null)))

    /** One change a caller wants: [value] for [key], or its removal when null. */
    data class Edit(val key: String, val value: JsonElement?)

    /**
     * Sends [edits] as one all-or-nothing proposal, each based on the revision of its entry as last
     * learned. While connected to a capable host whose document is not live (a read or a write failed
     * since the connection came up), it reads the document first, so one transient failure never leaves
     * the state read-only until a reconnect.
     */
    suspend fun propose(edits: List<Edit>): ShellStateEditOutcome = this.edits.withLock {
        if (!link.isConnected) return@withLock ShellStateEditOutcome.OFFLINE
        if (!supported) return@withLock ShellStateEditOutcome.UNSUPPORTED
        if (!mutableState.value.editable) refresh()
        val known = mutableState.value
        val base = known.snapshot
        if (!known.editable || base == null) ShellStateEditOutcome.OFFLINE else send(base, edits)
    }

    private suspend fun send(base: ShellStateSnapshot, edits: List<Edit>): ShellStateEditOutcome {
        val changes = edits.map { ShellStateChange(it.key, it.value, base.entries[it.key]?.revision ?: 0L) }
        val params = buildJsonObject { put(ShellStateWire.CHANGES, JsonArray(changes.map(ShellStateChange::toJson))) }
        val reply = runCatching { link.call(ShellStateWire.PROPOSE, params) }
        reply.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val answer = reply.getOrNull()
        val accepted = answer?.takeIf { it.success }?.result?.let(::decode)
        if (accepted != null) {
            adopt(accepted, live = true)
            return ShellStateEditOutcome.APPLIED
        }
        val error = ShellStateError.fromWire(answer?.error)
        mutableState.update { it.copy(error = error, live = false) }
        Telemetry.event(TAG, "edit.failed", "error" attr error.name, "revision" attr base.documentRevision)
        return when (error) {
            ShellStateError.UNREACHABLE -> ShellStateEditOutcome.OFFLINE
            ShellStateError.REVISION_CONFLICT -> {
                refresh()
                ShellStateEditOutcome.CONFLICT
            }
            else -> ShellStateEditOutcome.REFUSED
        }
    }

    private suspend fun onPush(frame: JsonObject) {
        val pushed = decode(frame) ?: return
        adopt(pushed, live = false)
    }

    /**
     * Takes [snapshot] unless a newer one from this connection is already shown, whether or not it was read
     * live: a read reply that arrives after a newer push never replaces it. Anything from an earlier connection
     * is replaced by whatever this connection says first. [live] only ever turns on: a successful read or write
     * passes true, a push passes false and leaves the current value as it is (read inside the update, so a
     * concurrent failure mark is never undone); only a failed call or a disconnect turns it off.
     */
    private suspend fun adopt(snapshot: ShellStateSnapshot, live: Boolean) = adopting.withLock {
        val shownRevision = mutableState.value.snapshot?.documentRevision ?: -1L
        val keepShown = shownFromThisConnection && shownRevision >= snapshot.documentRevision
        if (!keepShown) shownFromThisConnection = true
        mutableState.update { current ->
            current.copy(snapshot = if (keepShown) current.snapshot else snapshot, live = live || current.live, error = null)
        }
    }

    /** One request whose result is the document; null on any failure, with its code in [state]. */
    private suspend fun request(method: String, params: JsonObject): ShellStateSnapshot? {
        val outcome = runCatching { link.call(method, params) }
        (outcome.exceptionOrNull() as? CancellationException)?.let { throw it }
        val reply = outcome.getOrNull()
        val document = reply?.takeIf { it.success }?.result?.let(::decode)
        if (document == null) mutableState.update { it.copy(error = ShellStateError.fromWire(reply?.error)) }
        return document
    }

    /** The document in [element], or null when it is unreadable or of a newer schema than this build reads. */
    private fun decode(element: JsonElement): ShellStateSnapshot? =
        runCatching { LettaJson.wire.decodeFromJsonElement(ShellStateSnapshot.serializer(), element) }.getOrNull()
            ?.takeIf { it.schemaVersion <= ShellStateSchema.VERSION }

    private companion object {
        const val TAG = "SharedShellState"
    }
}
