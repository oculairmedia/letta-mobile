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
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 *
 * ## One model for every interleaving
 *
 * Replies, pushes and connection changes arrive from different connections and from calls that were already
 * in flight, so nothing here is decided from a flag read earlier. Instead:
 * - [generation] goes up on every connection transition (a new session, a drop, a connected host without the
 *   contract, an offline refresh). The connection is observed by session identity, so a drop and reconnect
 *   queued before the collector runs still count, and without waiting for any read.
 * - Every request captures the generation it was issued under; a push carries the generation current when it
 *   is handled. A reply is applied only if its generation is still current: anything from an earlier
 *   connection is dropped, whatever its revision.
 * - The shown document remembers the generation it came from. A newer-or-equal revision from the current
 *   generation is kept over an older one; a document from an earlier generation is replaced by whatever the
 *   current one says first (revisions only order documents of one connection).
 * - [ShellStateState.live] turns on only through a successful read or write of the current generation, and
 *   off through a failure of the current generation or any transition. A push never touches it and nothing
 *   restores a value captured earlier.
 * All of it changes under one lock, so deciding and applying are a single step.
 */
class SharedShellState(
    private val link: ShellStateLink,
) {
    private val edits = Mutex()
    private val mutableState = MutableStateFlow(ShellStateState())

    /** Held across every change of the state, [generation] and [shownGeneration]. */
    private val model = Mutex()

    /** The connection epoch: bumped by every [transition]. Guarded by [model]. */
    private var generation = 0L

    /** The [generation] the shown snapshot was adopted under; no generation equals [NO_GENERATION]. Guarded by [model]. */
    private var shownGeneration = NO_GENERATION

    /** What this device knows now. */
    val state: StateFlow<ShellStateState> = mutableState.asStateFlow()

    /** Whether the connected host serves the contract right now. */
    val supported: Boolean get() = ShellStateWire.CAPABILITY in link.hostCapabilities

    /**
     * Follows the connection and the host's pushes until cancelled: reads again on every reconnect. A
     * connection change is handled at once, never behind a pending read: the previous connection's read is
     * cancelled and the new one's started on its own coroutine.
     */
    suspend fun run() = coroutineScope {
        link.pushes.onEach(::onPush).launchIn(this)
        var read: Job? = null
        link.connection.distinctUntilChanged().collect { session ->
            read?.cancel()
            transition { it.copy(live = false) }
            read = if (session != null) launch { refresh() } else null
        }
    }

    /** Reads the host's document; a host without the contract leaves [state] unsupported. */
    suspend fun refresh(): ShellStateState {
        if (!supported) {
            // While disconnected the link advertises nothing, which says nothing about the host: keep the last
            // document for offline display. A connected host without the contract has no state to show, and
            // another host's document must not linger.
            val connected = link.isConnected
            transition { if (connected) it.copy(supported = false, live = false, snapshot = null) else it.copy(live = false) }
            return mutableState.value
        }
        val issuedUnder = model.withLock { generation.also { mutableState.update { it.copy(supported = true) } } }
        request(issuedUnder, ShellStateWire.GET, JsonObject(emptyMap())).document?.let { adopt(it, issuedUnder, live = true) }
        return mutableState.value
    }

    /** Starts a new generation and applies [change] with it: the one place the connection epoch moves. */
    private suspend fun transition(change: (ShellStateState) -> ShellStateState) = model.withLock {
        generation += 1
        mutableState.update(change)
    }

    /** Applies [change] only while [issuedUnder] is still the current generation. */
    private suspend fun settle(issuedUnder: Long, change: (ShellStateState) -> ShellStateState) = model.withLock {
        if (issuedUnder == generation) mutableState.update(change)
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
        val (known, issuedUnder) = model.withLock { mutableState.value to generation }
        val base = known.snapshot
        if (!known.editable || base == null) ShellStateEditOutcome.OFFLINE else send(base, issuedUnder, edits)
    }

    private suspend fun send(base: ShellStateSnapshot, issuedUnder: Long, edits: List<Edit>): ShellStateEditOutcome {
        val changes = edits.map { ShellStateChange(it.key, it.value, base.entries[it.key]?.revision ?: 0L) }
        val params = buildJsonObject { put(ShellStateWire.CHANGES, JsonArray(changes.map(ShellStateChange::toJson))) }
        val reply = request(issuedUnder, ShellStateWire.PROPOSE, params)
        reply.document?.let {
            adopt(it, issuedUnder, live = true)
            return ShellStateEditOutcome.APPLIED
        }
        val error = reply.error
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

    /** A push belongs to whatever generation is current when it is handled, and never touches `live`. */
    private suspend fun onPush(frame: JsonObject) {
        val pushed = decode(frame) ?: return
        adopt(pushed, issuedUnder = null, live = false)
    }

    /**
     * Applies [snapshot] unless it is out of date. [issuedUnder] is the generation its request captured (null
     * for a push, which takes the current one): a reply of an earlier generation is dropped. Within the current
     * generation a document that is not newer than the shown one is kept out, whether the shown one was read
     * or pushed. [live] only ever turns on, and only for a successful read or write; a push passes false and
     * leaves the value as it is.
     */
    private suspend fun adopt(snapshot: ShellStateSnapshot, issuedUnder: Long?, live: Boolean) = model.withLock {
        if (issuedUnder != null && issuedUnder != generation) return@withLock
        val shownRevision = mutableState.value.snapshot?.documentRevision ?: -1L
        val keepShown = shownGeneration == generation && shownRevision >= snapshot.documentRevision
        if (!keepShown) shownGeneration = generation
        mutableState.update { current ->
            current.copy(snapshot = if (keepShown) current.snapshot else snapshot, live = live || current.live, error = null)
        }
    }

    /** What one request came to: the [document] it answered, or else the [error] it failed with. */
    private class Reply(val document: ShellStateSnapshot?, val error: ShellStateError)

    /**
     * One request. A failure of the current generation records its code and turns `live` off; one of an
     * earlier generation changes nothing.
     */
    private suspend fun request(issuedUnder: Long, method: String, params: JsonObject): Reply {
        val outcome = runCatching { link.call(method, params) }
        (outcome.exceptionOrNull() as? CancellationException)?.let { throw it }
        val reply = outcome.getOrNull()
        val document = reply?.takeIf { it.success }?.result?.let(::decode)
        val error = ShellStateError.fromWire(reply?.error)
        if (document == null) settle(issuedUnder) { it.copy(error = error, live = false) }
        return Reply(document, error)
    }

    /** The document in [element], or null when it is unreadable or of a newer schema than this build reads. */
    private fun decode(element: JsonElement): ShellStateSnapshot? =
        runCatching { LettaJson.wire.decodeFromJsonElement(ShellStateSnapshot.serializer(), element) }.getOrNull()
            ?.takeIf { it.schemaVersion <= ShellStateSchema.VERSION }

    private companion object {
        const val TAG = "SharedShellState"
        const val NO_GENERATION = -1L
    }
}
