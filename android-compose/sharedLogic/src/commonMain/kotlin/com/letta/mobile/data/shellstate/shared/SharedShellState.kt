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
 * - Every request captures the generation it was issued under, and the link's connection identity, read
 *   synchronously (the collector that bumps the generation may not have run yet); a push carries where it was
 *   received. A reply is applied only if both are still current: anything from an earlier
 *   connection is dropped, whatever its revision. The shown document also remembers the connection identity it
 *   was read on, and a proposal refreshes first unless that is the connection now, so no edit is ever based on
 *   another connection's revisions.
 * - The shown document remembers the generation it came from. A newer-or-equal revision from the current
 *   generation is kept over an older one; a document from an earlier generation is replaced by whatever the
 *   current one says first (revisions only order documents of one connection).
 * - [ShellStateState.live] turns on only through a successful read or write of the current generation, and
 *   off through a failure of the current generation or any transition. A push never touches it and nothing
 *   restores a value captured earlier.
 * - A failure is judged the same way as a success: a refusal that arrives from a generation or connection that is
 *   no longer current says nothing about the host now, so the edit reports [ShellStateEditOutcome.OFFLINE]
 *   (never a conflict or a refusal) and nothing is refreshed or recorded.
 * All of it changes under one lock, so deciding and applying are a single step. The bookkeeping itself lives in
 * [ConnectionLedger]; this class orchestrates the link, the lock and the state flow.
 */
class SharedShellState(
    private val link: ShellStateLink,
) {
    private val edits = Mutex()
    private val mutableState = MutableStateFlow(ShellStateState())

    /** Held across every change of the state and of [ledger]. */
    private val model = Mutex()
    private val ledger = ConnectionLedger { link.currentConnection }

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
        // A push is bound to the connection it was received on: where it arrived is recorded before anything
        // suspends, and it is adopted only if that is still the current generation and connection, so one received
        // on A never becomes B's document. It never touches `live`.
        link.pushes.onEach { frame ->
            val receivedOn = ledger.issue()
            decode(frame)?.let { adopt(it, receivedOn, live = false) }
        }.launchIn(this)
        var read: Job? = null
        link.connection.distinctUntilChanged().collect { session ->
            read?.cancel()
            transition { it.copy(live = false) }
            read = if (session != null) launch { refresh() } else null
        }
    }

    /** Reads the host's document; a host without the contract leaves [state] unsupported. */
    suspend fun refresh(): ShellStateState {
        if (supported) readDocument() else noteUnsupported()
        return mutableState.value
    }

    private suspend fun readDocument() {
        val issued = model.withLock {
            mutableState.update { it.copy(supported = true) }
            ledger.issue()
        }
        request(issued, ShellStateWire.GET, JsonObject(emptyMap())).document?.let { adopt(it, issued, live = true) }
    }

    /**
     * While disconnected the link advertises nothing, which says nothing about the host: keep the last document
     * for offline display. A connected host without the contract has no state to show, and another host's
     * document must not linger.
     */
    private suspend fun noteUnsupported() {
        val connected = link.isConnected
        transition { if (connected) it.copy(supported = false, live = false, snapshot = null) else it.copy(live = false) }
    }

    /** Starts a new generation and applies [change] with it: the one place the connection epoch moves. */
    private suspend fun transition(change: (ShellStateState) -> ShellStateState) = model.withLock {
        mutableState.update(change)
        ledger.advance(documentShown = mutableState.value.snapshot != null)
    }

    /** Applies [change] only while [issued] is still current; whether it was. */
    private suspend fun settle(issued: Issue, change: (ShellStateState) -> ShellStateState): Boolean = model.withLock {
        ledger.isCurrent(issued).also { current -> if (current) mutableState.update(change) }
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
        when {
            !link.isConnected -> ShellStateEditOutcome.OFFLINE
            !supported -> ShellStateEditOutcome.UNSUPPORTED
            else -> proposeOnCurrentBaseline(edits)
        }
    }

    private suspend fun proposeOnCurrentBaseline(edits: List<Edit>): ShellStateEditOutcome {
        if (!baselineIsCurrent()) refresh()
        val (known, issued) = model.withLock { mutableState.value to ledger.issue() }
        val base = known.snapshot
        val sendable = base != null && known.editable && baselineIsCurrent()
        return if (sendable) send(base, issued, edits) else ShellStateEditOutcome.OFFLINE
    }

    /** Whether the shown document is live and was read on the connection that is up now. */
    private suspend fun baselineIsCurrent(): Boolean =
        model.withLock { mutableState.value.editable && ledger.shownOnCurrentConnection() }

    private suspend fun send(base: ShellStateSnapshot, issued: Issue, edits: List<Edit>): ShellStateEditOutcome {
        val changes = edits.map { ShellStateChange(it.key, it.value, base.entries[it.key]?.revision ?: 0L) }
        val params = buildJsonObject { put(ShellStateWire.CHANGES, JsonArray(changes.map(ShellStateChange::toJson))) }
        val reply = request(issued, ShellStateWire.PROPOSE, params)
        val written = reply.document?.let { adopt(it, issued, live = true) }
        if (written != null) return if (written) ShellStateEditOutcome.APPLIED else ShellStateEditOutcome.OFFLINE
        // A refusal that comes from a connection that is gone says nothing about the host now.
        if (!reply.current) return ShellStateEditOutcome.OFFLINE
        Telemetry.event(TAG, "edit.failed", "error" attr reply.error.name, "revision" attr base.documentRevision)
        if (reply.error == ShellStateError.REVISION_CONFLICT) refresh()
        return reply.error.outcome()
    }

    /**
     * Applies [snapshot] unless it is out of date; whether it was applied or kept out by recency (a dropped reply
     * reports false). [issued] is where its request was issued, or where a push was received. [ConnectionLedger.admit] decides.
     * [live] only ever turns on, and only for a successful read or write; a push passes false and leaves the
     * value as it is.
     */
    private suspend fun adopt(snapshot: ShellStateSnapshot, issued: Issue, live: Boolean): Boolean = model.withLock {
        val shownRevision = mutableState.value.snapshot?.documentRevision ?: NO_REVISION
        val admission = ledger.admit(issued, snapshot.documentRevision, shownRevision)
        if (admission == Admission.REPLACE) ledger.recordShown(issued)
        if (admission != Admission.DROP) {
            mutableState.update { current ->
                val shown = if (admission == Admission.REPLACE) snapshot else current.snapshot
                current.copy(snapshot = shown, live = live || current.live, error = null)
            }
        }
        admission != Admission.DROP
    }

    /**
     * What one request came to: the [document] it answered, or else the [error] it failed with. [current] is
     * whether the request was still current when it came back.
     */
    private class Reply(val document: ShellStateSnapshot?, val error: ShellStateError, val current: Boolean)

    /**
     * One request. A failure of the current generation and connection records its code and turns `live` off;
     * one issued on an earlier one changes nothing and says so in [Reply.current].
     */
    private suspend fun request(issued: Issue, method: String, params: JsonObject): Reply {
        val outcome = runCatching { link.call(method, params) }
        (outcome.exceptionOrNull() as? CancellationException)?.let { throw it }
        val reply = outcome.getOrNull()
        val document = reply?.takeIf { it.success }?.result?.let(::decode)
        val error = ShellStateError.fromWire(reply?.error)
        val current = document != null || settle(issued) { it.copy(error = error, live = false) }
        return Reply(document, error, current)
    }

    private companion object {
        const val TAG = "SharedShellState"
        const val NO_REVISION = -1L
    }
}

/** How a refusal of the current connection ends an edit: the host's own answer is final, a lost link is offline. */
private fun ShellStateError.outcome(): ShellStateEditOutcome = when (this) {
    ShellStateError.UNREACHABLE -> ShellStateEditOutcome.OFFLINE
    ShellStateError.REVISION_CONFLICT -> ShellStateEditOutcome.CONFLICT
    else -> ShellStateEditOutcome.REFUSED
}

/** The document in [element], or null when it is unreadable or of a newer schema than this build reads. */
private fun decode(element: JsonElement): ShellStateSnapshot? =
    runCatching { LettaJson.wire.decodeFromJsonElement(ShellStateSnapshot.serializer(), element) }.getOrNull()
        ?.takeIf { it.schemaVersion <= ShellStateSchema.VERSION }
