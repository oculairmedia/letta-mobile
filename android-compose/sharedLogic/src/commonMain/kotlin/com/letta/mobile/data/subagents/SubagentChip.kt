package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * letta-mobile-lgns8.22.8 / letta-mobile-5hihw: the explicit subagent-chip
 * lifecycle state machine.
 *
 * Before this bead the chip state was whatever `status` string the last
 * observed frame carried, so a chip could silently move backwards
 * (`completed` → `running`) or be marked terminal by something that is not an
 * authoritative subagent lifecycle event (5hihw: chips rendered "completed"
 * as soon as the PARENT assistant turn finished). The state machine below is
 * the single place a chip's lifecycle may advance, and every illegal
 * transition is rejected and telemetered rather than applied.
 *
 * LEGAL TRANSITIONS
 * ```
 *   OBSERVED ─┬─▶ RUNNING ─┬─▶ COMPLETED   (authoritative completion event)
 *             │            ├─▶ FAILED      (authoritative failure event)
 *             │            ├─▶ CANCELLED   (kill / TaskStop / evicted)
 *             │            └─▶ ORPHANED    (reconciler: no live counterpart)
 *             └───────────────▶ (any terminal, for very short subagents)
 *
 *   <terminal> ─▶ same terminal   (idempotent replay: metadata backfill only)
 *   <terminal> ─▶ anything else   (ILLEGAL — rejected + telemetry)
 * ```
 *
 * Note the absence of any edge that a parent-turn terminal could take: see
 * [DurableSubagentRegistry.markParentTurnEnded].
 */
enum class SubagentChipState {
    /** Dispatch seen, no lifecycle event yet. Renders as pending/in-progress. */
    OBSERVED,

    /** Authoritatively running. */
    RUNNING,

    /** Clean terminal. */
    COMPLETED,

    /** Authoritative failure terminal. */
    FAILED,

    /** Non-clean terminal: killed / TaskStop'd / evicted. */
    CANCELLED,

    /**
     * Reconciliation terminal (lgns8.22.8): the chip survived a controller
     * restart but the live runtime has no counterpart for it. Never silently
     * dropped and never left spinning forever.
     */
    ORPHANED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == ORPHANED

    /** @return true when `this` → [next] is a legal lifecycle edge. */
    fun canAdvanceTo(next: SubagentChipState): Boolean = when {
        this == next -> true
        isTerminal -> false
        this == OBSERVED -> true
        this == RUNNING -> next.isTerminal
        else -> false
    }

    /**
     * Wire vocabulary (`MOBILE_WS_PROTOCOL.md` §13.2) for this state.
     * [ORPHANED] maps to `cancelled` — the documented non-clean terminal
     * (letta-mobile-drv4a explicitly names "orphaned" as a `cancelled` cause)
     * so existing clients render a terminal chip without a wire change.
     */
    fun toWireStatus(): String = when (this) {
        OBSERVED, RUNNING -> SubagentStatus.RUNNING
        COMPLETED -> SubagentStatus.COMPLETED
        FAILED -> SubagentStatus.FAILED
        CANCELLED, ORPHANED -> SubagentStatus.CANCELLED
    }

    companion object {
        /**
         * Map an observed wire `status` string onto the state machine. Unknown
         * vocabulary is conservatively treated as [OBSERVED] (pending) rather
         * than as a terminal — never invent a completion (5hihw).
         */
        fun fromWireStatus(status: String?): SubagentChipState = when (status?.lowercase()) {
            null, "" -> OBSERVED
            "pending", "queued", "dispatched", "observed", "starting" -> OBSERVED
            SubagentStatus.RUNNING, "in_progress", "active" -> RUNNING
            SubagentStatus.COMPLETED, "complete", "success", "succeeded", "done" -> COMPLETED
            SubagentStatus.FAILED, "error", "errored" -> FAILED
            SubagentStatus.CANCELLED, "canceled", "killed", "stopped", "evicted" -> CANCELLED
            "orphaned", "unknown" -> ORPHANED
            else -> OBSERVED
        }
    }
}

/**
 * letta-mobile-7vs4s: WHICH producer a chip fact came from, and the total
 * order between competing producers.
 *
 * The same chip can be described by three independent producers at once, and
 * before this bead the last writer won — which is how local-runtime mode ended
 * up rendering a frozen SHIM snapshot that no live producer could ever clear.
 * Precedence is now an explicit, total, documented order:
 *
 * | source              | precedence | authority |
 * |---------------------|-----------:|-----------|
 * | [CORRELATOR_OBSERVED] | 10 | inferred locally from parent tool_call frames — weakest; can create a chip but never overrule a real registry |
 * | [HTTP_REGISTRY]       | 20 | LettaShim HTTP registry (TTL + breaker, letta-mobile-#1011) — a remote cache, may be stale |
 * | [CONTROLLER_NATIVE]   | 30 | `update_subagent_state` from the App Server the controller owns — the source of truth |
 *
 * RULE: an observation is applied only when its source precedence is **>=**
 * the precedence of the source that last wrote the record. A weaker source is
 * rejected (telemetered `source.rejected`), so a stale HTTP/correlator view
 * can never overwrite controller-native truth, and the outcome does not depend
 * on frame arrival order.
 */
enum class SubagentChipSource(val precedence: Int) {
    CORRELATOR_OBSERVED(10),
    HTTP_REGISTRY(20),
    CONTROLLER_NATIVE(30),
}

/**
 * One durable subagent chip. Persisted verbatim, so a controller restart
 * rehydrates the full chip (identity + provenance + lifecycle + last-seen)
 * rather than an empty registry.
 *
 * Keyed by ([conversationId], [agentId], [toolCallId]) — see
 * [DurableSubagentRegistry] for why generation is data and not key.
 */
@Serializable
data class SubagentChipRecord(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("tool_call_id") val toolCallId: String,
    val state: SubagentChipState,
    val source: SubagentChipSource,
    val description: String = "",
    @SerialName("subagent_type") val subagentType: String = "",
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("subagent_agent_id") val subagentAgentId: String? = null,
    @SerialName("subagent_conversation_id") val subagentConversationId: String? = null,
    @SerialName("parent_run_id") val parentRunId: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    /**
     * Controller connection generation that last touched this chip. Data, not
     * identity: reconciliation uses it to tell "seen on the current connection"
     * from "rehydrated from a previous process".
     */
    val generation: Long = 0,
    @SerialName("first_seen_epoch_ms") val firstSeenEpochMs: Long = 0,
    @SerialName("last_seen_epoch_ms") val lastSeenEpochMs: Long = 0,
    @SerialName("terminal_at_epoch_ms") val terminalAtEpochMs: Long? = null,
) {
    val key: SubagentChipKey get() = SubagentChipKey(conversationId, agentId, toolCallId)

    fun toEntry(): SubagentEntry = SubagentEntry(
        toolCallId = toolCallId,
        description = description,
        subagentType = subagentType,
        status = state.toWireStatus(),
        taskId = taskId,
        subagentAgentId = subagentAgentId,
        subagentConversationId = subagentConversationId,
        parentRunId = parentRunId,
        parentAgentId = agentId,
        parentConversationId = conversationId,
        startedAt = startedAt,
        terminalAtEpochMs = terminalAtEpochMs,
    )
}

/**
 * Durable chip identity.
 *
 * The bead sketched (agentId, taskId, generation). Shipped identity is
 * (conversationId, agentId, toolCallId):
 *  - `toolCallId` is the canonical correlation key the whole codebase already
 *    uses ([SubagentEntry.toolCallId]); `taskId` is only present once a
 *    background dispatch has RETURNED identity, so keying on it would leave
 *    every not-yet-returned chip unkeyable.
 *  - `conversationId` is in the key because the admin surface is
 *    conversation-scoped and chips must stay isolated per parent
 *    (letta-mobile-or40x: one process-wide slot is the defect class).
 *  - `generation` is deliberately NOT in the key: a chip must be recognised as
 *    the SAME chip across a controller restart, which is the entire point of
 *    this bead. It is carried as data on [SubagentChipRecord.generation].
 */
data class SubagentChipKey(
    val conversationId: String,
    val agentId: String?,
    val toolCallId: String,
)

/** One producer's view of a chip, fed to [DurableSubagentRegistry.observe]. */
data class SubagentChipObservation(
    val conversationId: String,
    val agentId: String?,
    val toolCallId: String,
    val state: SubagentChipState,
    val source: SubagentChipSource,
    val description: String = "",
    val subagentType: String = "",
    val taskId: String? = null,
    val subagentAgentId: String? = null,
    val subagentConversationId: String? = null,
    val parentRunId: String? = null,
    val startedAt: String? = null,
    val generation: Long = 0,
) {
    val key: SubagentChipKey get() = SubagentChipKey(conversationId, agentId, toolCallId)

    companion object {
        /** Lift a wire [SubagentEntry] into an observation from [source]. */
        fun fromEntry(
            entry: SubagentEntry,
            conversationId: String,
            agentId: String?,
            source: SubagentChipSource,
            generation: Long = 0,
        ): SubagentChipObservation = SubagentChipObservation(
            conversationId = conversationId,
            agentId = agentId ?: entry.parentAgentId,
            toolCallId = entry.toolCallId,
            state = SubagentChipState.fromWireStatus(entry.status),
            source = source,
            description = entry.description,
            subagentType = entry.subagentType,
            taskId = entry.taskId,
            subagentAgentId = entry.subagentAgentId,
            subagentConversationId = entry.subagentConversationId,
            parentRunId = entry.parentRunId,
            startedAt = entry.startedAt,
            generation = generation,
        )
    }
}
