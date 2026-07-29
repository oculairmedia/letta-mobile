package com.letta.mobile.data.controller.fanout

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Correlates server→client control frames (lgns8.22.4).
 *
 * Distinct from the transport [com.letta.mobile.data.transport.appserver.AppServerRequestRegistry],
 * which tracks client→server RPC responses. This registry owns inbound
 * `control_request` / `external_tool_call_request` identity so:
 * - the same [requestId] is delivered / answered at most once per generation
 * - disconnect can supersede every pending entry for that generation
 * - a turn lease can claim exclusive handling before answering
 *
 * Full approval/tool execution ledgers (retry, sync recover, controller API)
 * remain in lgns8.22.5.
 */
class InboundControlRequestRegistry {
    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<EntryKey, Entry>()
    private var failedGeneration: Long? = null

    enum class Kind { Approval, ExternalTool }

    enum class State { Pending, Dispatched, Claimed, Answered, Superseded }


    data class EntryKey(
        val requestId: String,
        val connectionGeneration: Long,
    )

    data class Entry(
        val requestId: String,
        val kind: Kind,
        val connectionGeneration: Long,
        val agentId: String? = null,
        val conversationId: String? = null,
        val toolCallId: String? = null,
        val state: State = State.Pending,
        val leaseToken: Long? = null,
    )

    sealed class RegisterResult {
        data class Accepted(val entry: Entry) : RegisterResult()
        data class Duplicate(val entry: Entry) : RegisterResult()
        data class GenerationFailed(val generation: Long) : RegisterResult()
    }

    data class RegisterRequest(
        val requestId: String,
        val kind: Kind,
        val connectionGeneration: Long,
        val agentId: String? = null,
        val conversationId: String? = null,
        val toolCallId: String? = null,
    )

    fun register(request: RegisterRequest): RegisterResult = synchronized(lock) {
        failedGeneration?.let { failed ->
            if (request.connectionGeneration <= failed) {
                return RegisterResult.GenerationFailed(request.connectionGeneration)
            }
        }
        val key = EntryKey(request.requestId, request.connectionGeneration)
        entries[key]?.let { existing ->
            return RegisterResult.Duplicate(existing)
        }
        val entry = Entry(
            requestId = request.requestId,
            kind = request.kind,
            connectionGeneration = request.connectionGeneration,
            agentId = request.agentId,
            conversationId = request.conversationId,
            toolCallId = request.toolCallId,
        )
        entries[key] = entry
        RegisterResult.Accepted(entry)
    }

    /**
     * Claim exclusive handling for [requestId]. Returns true when transitioning
     * Pending/Dispatched → Claimed (first observer). Subsequent calls return false
     * even for the owning lease so replays are not re-mapped into duplicate UI events.
     */
    fun tryClaim(
        requestId: String,
        leaseToken: Long,
        connectionGeneration: Long,
    ): Boolean = synchronized(lock) {
        failedGeneration?.let { failed ->
            if (connectionGeneration <= failed) return false
        }
        val key = EntryKey(requestId, connectionGeneration)
        val entry = entries[key] ?: return false
        when (entry.state) {
            State.Pending, State.Dispatched -> {
                entries[key] = entry.copy(state = State.Claimed, leaseToken = leaseToken)
                true
            }
            State.Claimed, State.Answered, State.Superseded -> false
        }
    }

    /** Mark that fanout delivered the frame to at least one subscriber. */
    fun markDispatched(requestId: String, connectionGeneration: Long) {
        synchronized(lock) {
            val key = EntryKey(requestId, connectionGeneration)
            val entry = entries[key] ?: return
            if (entry.state == State.Pending) {
                entries[key] = entry.copy(state = State.Dispatched)
            }
        }
    }

    /** True when [leaseToken] currently owns a Claimed (not yet Answered) entry. */
    fun ownsClaim(
        requestId: String,
        leaseToken: Long,
        connectionGeneration: Long,
    ): Boolean = synchronized(lock) {
        val entry = entries[EntryKey(requestId, connectionGeneration)] ?: return false
        entry.state == State.Claimed && entry.leaseToken == leaseToken
    }

    fun markAnswered(requestId: String, connectionGeneration: Long) {
        synchronized(lock) {
            val key = EntryKey(requestId, connectionGeneration)
            val entry = entries[key] ?: return
            entries[key] = entry.copy(state = State.Answered)
        }
    }

    /**
     * Return a failed claim to [State.Pending] so a later server replay can be
     * answered (e.g. send failed because the transport dropped mid-response).
     */
    fun releaseClaim(requestId: String, leaseToken: Long, connectionGeneration: Long) {
        synchronized(lock) {
            val key = EntryKey(requestId, connectionGeneration)
            val entry = entries[key] ?: return
            if (entry.state == State.Claimed && entry.leaseToken == leaseToken) {
                entries[key] = entry.copy(state = State.Pending, leaseToken = null)
            }
        }
    }

    /**
     * Drop or reopen every Claimed entry owned by [leaseToken] when that lease
     * exits without answering (cancel / failure). Prevents a successor lease on
     * the same generation from being permanently blocked on Claimed leftovers.
     */
    fun releaseClaimsForLease(leaseToken: Long, connectionGeneration: Long) {
        synchronized(lock) {
            for ((key, entry) in entries.toList()) {
                if (!entry.shouldReleaseFor(leaseToken, connectionGeneration)) continue
                entries[key] = entry.copy(state = State.Pending, leaseToken = null)
            }
        }
    }

    private fun Entry.shouldReleaseFor(leaseToken: Long, connectionGeneration: Long): Boolean =
        this.connectionGeneration == connectionGeneration &&
            state == State.Claimed &&
            this.leaseToken == leaseToken

    data class BindRequest(
        val requestId: String,
        val leaseToken: Long,
        val agentId: String,
        val conversationId: String,
        val connectionGeneration: Long,
    )

    fun bindLease(request: BindRequest) {
        synchronized(lock) {
            val key = EntryKey(request.requestId, request.connectionGeneration)
            val entry = entries[key] ?: return
            entries[key] = entry.copy(
                leaseToken = request.leaseToken,
                agentId = request.agentId,
                conversationId = request.conversationId,
            )
        }
    }

    fun lookup(requestId: String, connectionGeneration: Long): Entry? =
        synchronized(lock) { entries[EntryKey(requestId, connectionGeneration)] }

    /**
     * True when a lease may still observe the frame (Pending or Dispatched).
     * Claimed/Answered entries are not redelivered into the turn mapper.
     */
    fun isDeliverableTo(
        requestId: String,
        leaseToken: Long,
        connectionGeneration: Long,
    ): Boolean = synchronized(lock) {
        failedGeneration?.let { failed ->
            if (connectionGeneration <= failed) return false
        }
        val entry = entries[EntryKey(requestId, connectionGeneration)] ?: return false
        entry.state == State.Pending || entry.state == State.Dispatched
    }

    /**
     * Drop every entry at or below [generation] so the same request_id can be
     * re-registered on a successor connection. Idempotent for the same gen.
     */
    fun failGeneration(generation: Long) {
        synchronized(lock) {
            if (failedGeneration != null && generation < failedGeneration!!) return
            failedGeneration = generation
            val stale = entries.keys.filter { it.connectionGeneration <= generation }
            for (key in stale) {
                entries.remove(key)
            }
        }
    }

    fun pendingCount(): Int = synchronized(lock) {
        entries.values.count {
            it.state == State.Pending || it.state == State.Dispatched || it.state == State.Claimed
        }
    }
}
