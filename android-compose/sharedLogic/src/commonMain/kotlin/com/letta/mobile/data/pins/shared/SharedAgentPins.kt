package com.letta.mobile.data.pins.shared

import com.letta.mobile.data.pins.wire.AgentPinsError
import com.letta.mobile.data.pins.wire.AgentPinsWire
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What this device knows of the host's pinned agents. [snapshot] is the last list learned (from the
 * cache until the host answers, kept across a failed read); [live] is whether it was read from the
 * connected host since the connection came up. Edits need [editable].
 */
data class AgentPinsState(
    /** Whether the connected host serves the pinned-agents contract. */
    val supported: Boolean = false,
    /** The last list learned; null until one was. */
    val snapshot: AgentPinsSnapshot? = null,
    /** Whether [snapshot] was read from the connected host on this connection. */
    val live: Boolean = false,
    /** The last request's failure; null after a success. */
    val error: AgentPinsError? = null,
) {
    /** The pinned agents in order, as last learned. */
    val agentIds: List<AgentId> get() = snapshot?.agentIds.orEmpty()

    /** Whether an edit may be sent now. */
    val editable: Boolean get() = supported && live && snapshot != null
}

/** How one pin edit ended. */
enum class AgentPinsEditOutcome {
    /** The host accepted it (or it changed nothing). */
    APPLIED,

    /** Another device changed the list first; the edit was rolled back to the host's list. */
    CONFLICT,

    /** The host is out of reach or not read yet; nothing was sent or queued. */
    OFFLINE,

    /** The host does not keep shared pins. */
    UNSUPPORTED,

    /** The host refused the edit; it was rolled back. */
    REFUSED,
}

/**
 * The host's pinned agents, the one list every surface reads and edits.
 *
 * Edits are optimistic: the new list shows at once and is sent with the revision it was based on; the
 * host's answer replaces it, a conflict rolls it back to the host's current list, and nothing is ever
 * queued. While the host is out of reach the last list learned keeps showing and edits are refused
 * ([AgentPinsEditOutcome.OFFLINE]). Pushes from the host replace the list when they are newer. A host
 * that has never been written starts with an empty list; a host without the contract has no pins at
 * all, and no device-local list stands in for it. Nothing here logs an agent name; counts and
 * revisions only.
 */
class SharedAgentPins(
    private val link: AgentPinsLink,
    private val cache: AgentPinsCache,
) {
    private val edits = Mutex()
    private val mutableState = MutableStateFlow(AgentPinsState(snapshot = cache.read()))

    /** What this device knows now. */
    val state: StateFlow<AgentPinsState> = mutableState.asStateFlow()

    /** Whether the connected host serves the contract right now. */
    val supported: Boolean get() = AgentPinsWire.CAPABILITY in link.hostCapabilities

    /** Follows the connection and the host's pushes until cancelled: reads again on every reconnect. */
    suspend fun run() = coroutineScope {
        launch { link.pushes.collect(::onPush) }
        link.connection.distinctUntilChanged().collect { connected ->
            if (connected) refresh() else mutableState.update { it.copy(live = false) }
        }
    }

    /** Reads the host's list; a host without the contract leaves [state] unsupported. */
    suspend fun refresh(): AgentPinsState {
        if (!supported) return mutableState.updateAndGet { it.copy(supported = false, live = false) }
        mutableState.update { it.copy(supported = true) }
        val read = request(AgentPinsWire.GET, JsonObject(emptyMap()))
        if (read == null) {
            mutableState.update { it.copy(live = false) }
            return mutableState.value
        }
        adopt(read, live = true)
        return mutableState.value
    }

    /** Pins [agent] at the end of the list, or unpins it. */
    suspend fun setPinned(agent: AgentId, pinned: Boolean): AgentPinsEditOutcome = edit { ids ->
        when {
            !pinned -> ids - agent
            agent in ids -> ids
            else -> ids + agent
        }
    }

    /** Puts the list in [order]; pinned agents [order] leaves out keep their relative order after it. */
    suspend fun reorder(order: List<AgentId>): AgentPinsEditOutcome = edit { ids ->
        val placed = order.distinct().filter { it in ids }
        placed + ids.filterNot { it in placed }
    }

    /**
     * Sends one edit. While connected to a capable host whose list is not live (a read or a write failed
     * since the connection came up), it reads the list first, so one transient failure never leaves the
     * pins read-only until a reconnect.
     */
    private suspend fun edit(transform: (List<AgentId>) -> List<AgentId>): AgentPinsEditOutcome = edits.withLock {
        if (!supported) return@withLock AgentPinsEditOutcome.UNSUPPORTED
        if (!link.isConnected) return@withLock AgentPinsEditOutcome.OFFLINE
        if (!mutableState.value.editable) refresh()
        val known = mutableState.value
        val base = known.snapshot
        if (!known.editable || base == null) AgentPinsEditOutcome.OFFLINE else send(base, transform(base.agentIds))
    }

    private suspend fun send(base: AgentPinsSnapshot, next: List<AgentId>): AgentPinsEditOutcome {
        if (next == base.agentIds) return AgentPinsEditOutcome.APPLIED
        mutableState.update { it.copy(snapshot = base.copy(agentIds = next)) }
        val params = buildJsonObject {
            put(AgentPinsWire.AGENT_IDS, idsJson(next))
            put(AgentPinsWire.EXPECTED_REVISION, base.revision)
        }
        val reply = runCatching { link.call(AgentPinsWire.SET, params) }
        reply.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val answer = reply.getOrNull()
        val accepted = answer?.takeIf { it.success }?.result?.let(::decode)
        return when {
            accepted != null -> {
                mutableState.update { it.copy(error = null) }
                adopt(accepted, live = true)
                AgentPinsEditOutcome.APPLIED
            }
            answer == null -> rollBack(base, AgentPinsEditOutcome.OFFLINE, AgentPinsError.UNREACHABLE)
            AgentPinsError.fromWire(answer.error) == AgentPinsError.REVISION_CONFLICT -> {
                rollBack(base, AgentPinsEditOutcome.CONFLICT, AgentPinsError.REVISION_CONFLICT)
                refresh()
                AgentPinsEditOutcome.CONFLICT
            }
            else -> rollBack(base, AgentPinsEditOutcome.REFUSED, AgentPinsError.fromWire(answer.error))
        }
    }

    /** Restores [base] unless a newer list arrived meanwhile. */
    private fun rollBack(base: AgentPinsSnapshot, outcome: AgentPinsEditOutcome, code: AgentPinsError): AgentPinsEditOutcome {
        mutableState.update { current ->
            val restored = if (current.snapshot?.revision == base.revision) current.copy(snapshot = base) else current
            restored.copy(error = code, live = restored.live && outcome != AgentPinsEditOutcome.OFFLINE)
        }
        Telemetry.event(TAG, "edit.rolled_back", "outcome" attr outcome.name, "revision" attr base.revision)
        return outcome
    }

    private fun onPush(frame: JsonObject) {
        val pushed = decode(frame) ?: return
        val current = mutableState.value.snapshot
        if (current != null && pushed.revision <= current.revision) return
        adopt(pushed, live = mutableState.value.live)
    }

    /** Takes [snapshot] unless an equally new or newer one is already shown, and caches it. */
    private fun adopt(snapshot: AgentPinsSnapshot, live: Boolean) {
        mutableState.update { current ->
            val shown = current.snapshot
            val keep = shown != null && current.live && shown.revision > snapshot.revision
            current.copy(snapshot = if (keep) shown else snapshot, live = live || current.live, error = null)
        }
        mutableState.value.snapshot?.let(cache::write)
    }

    /** One request whose result is a list; null on any failure, with its code in [state]. */
    private suspend fun request(method: String, params: JsonObject): AgentPinsSnapshot? {
        val reply = runCatching { link.call(method, params) }
        reply.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        val answer = reply.getOrNull()
        val snapshot = answer?.takeIf { it.success }?.result?.let(::decode)
        if (snapshot == null) mutableState.update { it.copy(error = AgentPinsError.fromWire(answer?.error)) }
        return snapshot
    }

    private fun decode(element: JsonElement): AgentPinsSnapshot? =
        runCatching { LettaJson.wire.decodeFromJsonElement(AgentPinsSnapshot.serializer(), element) }.getOrNull()

    private fun idsJson(ids: List<AgentId>) = JsonArray(ids.map { JsonPrimitive(it.value) })

    private fun MutableStateFlow<AgentPinsState>.updateAndGet(transform: (AgentPinsState) -> AgentPinsState): AgentPinsState {
        update(transform)
        return value
    }

    private companion object {
        const val TAG = "SharedAgentPins"
    }
}
