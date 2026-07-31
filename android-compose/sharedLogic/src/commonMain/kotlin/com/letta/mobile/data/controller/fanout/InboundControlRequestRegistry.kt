package com.letta.mobile.data.controller.fanout

import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Correlates server→client control frames (lgns8.22.4).
 *
 * Distinct from the transport [com.letta.mobile.data.transport.appserver.AppServerRequestRegistry],
 * which tracks client→server RPC responses. This registry owns inbound
 * `control_request` / `external_tool_call_request` identity so:
 * - the same request identity is delivered / answered at most once per generation
 * - disconnect can supersede every pending entry for that generation
 * - a turn lease can claim exclusive handling before answering
 *
 * IDENTITY (lgns8.22.4.1.3): the App Server v2 contract declares
 * `external_tool_call_request` idempotency as `request_id_and_tool_call_id`, so
 * external-tool entries are keyed by (request_id, tool_call_id, generation).
 * Approvals carry no tool_call_id at this layer and stay request-id keyed
 * (tool_call_id null).
 *
 * MEMORY (lgns8.22.4.1.5): [entries] holds ONLY live work (Pending / Dispatched /
 * Claimed); it is naturally bounded by the number of in-flight requests. Answered
 * and superseded identities move into [completed], a bounded LRU dedup window,
 * so a long-lived tool-heavy connection cannot grow the registry without bound
 * and [failGeneration] never walks unbounded history.
 *
 * Full approval/tool execution ledgers (retry, sync recover, controller API)
 * remain in lgns8.22.5.
 */
class InboundControlRequestRegistry {
    private val lock = SynchronizedObject()

    /** Live entries only: Pending / Dispatched / Claimed. */
    private val entries = LinkedHashMap<EntryKey, Entry>()

    /**
     * Bounded completion watermark. Answered/superseded identities are retained
     * here purely so a server replay of an already-answered request is classified
     * [RegisterResult.Duplicate] (non-deliverable) instead of being re-executed.
     * Oldest-first eviction at [MAX_COMPLETED_HISTORY] with eviction telemetry.
     */
    private val completed = LinkedHashMap<EntryKey, Entry>()

    private var failedGeneration: Long? = null

    enum class Kind { Approval, ExternalTool }

    enum class State { Pending, Dispatched, Claimed, Answered, Superseded }

    /**
     * Identity of one inbound control request (lgns8.22.4.1.3).
     *
     * External tools are identified by (request_id, tool_call_id) — the App Server
     * v2 `request_id_and_tool_call_id` idempotency key. Approvals carry no
     * tool_call_id at this layer and leave it null.
     */
    data class RequestRef(
        val requestId: String,
        val toolCallId: String? = null,
    )

    data class EntryKey(
        val ref: RequestRef,
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
        val key = EntryKey(RequestRef(request.requestId, request.toolCallId), request.connectionGeneration)
        entries[key]?.let { existing ->
            return RegisterResult.Duplicate(existing)
        }
        completed[key]?.let { done ->
            return RegisterResult.Duplicate(done)
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
     * Claim exclusive handling for this request identity. Returns true when
     * transitioning Pending/Dispatched → Claimed (first observer). Subsequent
     * calls return false even for the owning lease so replays are not re-mapped
     * into duplicate UI events.
     */
    fun tryClaim(
        ref: RequestRef,
        leaseToken: Long,
        connectionGeneration: Long,
    ): Boolean = synchronized(lock) {
        failedGeneration?.let { failed ->
            if (connectionGeneration <= failed) return false
        }
        val key = EntryKey(ref, connectionGeneration)
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
    fun markDispatched(ref: RequestRef, connectionGeneration: Long) {
        synchronized(lock) {
            val key = EntryKey(ref, connectionGeneration)
            val entry = entries[key] ?: return
            if (entry.state == State.Pending) {
                entries[key] = entry.copy(state = State.Dispatched)
            }
        }
    }

    /** True when [leaseToken] currently owns a Claimed (not yet Answered) entry. */
    fun ownsClaim(
        ref: RequestRef,
        leaseToken: Long,
        connectionGeneration: Long,
    ): Boolean = synchronized(lock) {
        val entry = entries[EntryKey(ref, connectionGeneration)] ?: return false
        entry.state == State.Claimed && entry.leaseToken == leaseToken
    }

    /**
     * Retire the identity as answered.
     *
     * lgns8.22.4.1.4: [connectionGeneration] MUST be the generation the request
     * was claimed/sent on, never the live generation read after the send — a
     * recovery replay already registered under a successor generation must not be
     * marked answered by an old-connection response the server may never have seen.
     * A missing entry is a no-op by design (the claim generation already failed).
     */
    fun markAnswered(ref: RequestRef, connectionGeneration: Long) {
        synchronized(lock) {
            val key = EntryKey(ref, connectionGeneration)
            val entry = entries.remove(key) ?: return
            retainCompletedLocked(key, entry.copy(state = State.Answered))
        }
    }

    /**
     * Return a failed claim to [State.Pending] so a later server replay can be
     * answered (e.g. send failed because the transport dropped mid-response).
     */
    fun releaseClaim(
        ref: RequestRef,
        leaseToken: Long,
        connectionGeneration: Long,
    ) {
        synchronized(lock) {
            val key = EntryKey(ref, connectionGeneration)
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
        val toolCallId: String? = null,
    )

    fun bindLease(request: BindRequest) {
        synchronized(lock) {
            val key = EntryKey(RequestRef(request.requestId, request.toolCallId), request.connectionGeneration)
            val entry = entries[key] ?: return
            entries[key] = entry.copy(
                leaseToken = request.leaseToken,
                agentId = request.agentId,
                conversationId = request.conversationId,
            )
        }
    }

    fun lookup(ref: RequestRef, connectionGeneration: Long): Entry? =
        synchronized(lock) {
            val key = EntryKey(ref, connectionGeneration)
            entries[key] ?: completed[key]
        }

    /**
     * True when a lease may still observe the frame (Pending or Dispatched).
     * Claimed/Answered entries are not redelivered into the turn mapper.
     */
    fun isDeliverableTo(
        ref: RequestRef,
        leaseToken: Long,
        connectionGeneration: Long,
    ): Boolean = synchronized(lock) {
        failedGeneration?.let { failed ->
            if (connectionGeneration <= failed) return false
        }
        val entry = entries[EntryKey(ref, connectionGeneration)] ?: return false
        entry.state == State.Pending || entry.state == State.Dispatched
    }

    /**
     * Drop every entry at or below [generation] so the same request identity can
     * be re-registered on a successor connection. Idempotent for the same gen.
     *
     * Stays cheap for long sessions: [entries] holds only in-flight work and
     * [completed] is capped at [MAX_COMPLETED_HISTORY].
     */
    fun failGeneration(generation: Long) {
        synchronized(lock) {
            if (failedGeneration != null && generation < failedGeneration!!) return
            failedGeneration = generation
            entries.keys.filter { it.connectionGeneration <= generation }
                .forEach { entries.remove(it) }
            completed.keys.filter { it.connectionGeneration <= generation }
                .forEach { completed.remove(it) }
        }
    }

    fun pendingCount(): Int = synchronized(lock) {
        entries.values.count {
            it.state == State.Pending || it.state == State.Dispatched || it.state == State.Claimed
        }
    }

    /** Test/telemetry: size of the live (non-completed) entry map. */
    fun liveEntryCount(): Int = synchronized(lock) { entries.size }

    /** Test/telemetry: size of the bounded answered/superseded dedup window. */
    fun completedHistoryCount(): Int = synchronized(lock) { completed.size }

    private fun retainCompletedLocked(key: EntryKey, entry: Entry) {
        completed.remove(key)
        completed[key] = entry
        while (completed.size > MAX_COMPLETED_HISTORY) {
            val oldest = completed.keys.firstOrNull() ?: break
            completed.remove(oldest)
            Telemetry.event(
                "InboundControlRequestRegistry",
                "completedHistory.evicted",
                "requestId" to oldest.ref.requestId,
                "toolCallId" to (oldest.ref.toolCallId ?: ""),
                "generation" to oldest.connectionGeneration,
                "cap" to MAX_COMPLETED_HISTORY,
                level = Telemetry.Level.WARN,
            )
        }
    }

    companion object {
        /**
         * Bound on the answered/superseded dedup window (lgns8.22.4.1.5). Sized
         * well above any plausible in-flight replay horizon for one connection:
         * an eviction means a request answered >[MAX_COMPLETED_HISTORY] requests
         * ago was replayed, which is why eviction is recorded at WARN.
         */
        const val MAX_COMPLETED_HISTORY = 256
    }
}
