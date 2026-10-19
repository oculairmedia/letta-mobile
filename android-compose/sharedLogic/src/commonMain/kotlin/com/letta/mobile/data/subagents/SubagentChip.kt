package com.letta.mobile.data.subagents

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentActivitySnapshot
import com.letta.mobile.data.model.SubagentStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The explicit subagent-chip
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
 *   OBSERVED ─┬─▶ RUNNING ─┬─▶ COMPLETED (authoritative completion event)
 *             │            ├─▶ FAILED (authoritative failure event)
 *             │            ├─▶ CANCELLED (kill / TaskStop / evicted)
 *             │            └─▶ ORPHANED (reconciler: no live counterpart)
 *             └───────────────▶ (any terminal, for very short subagents)
 *
 *   <terminal> ─▶ same terminal (idempotent replay: metadata backfill only)
 *   <terminal> ─▶ anything else (ILLEGAL — rejected + telemetry)
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
     * Reconciliation terminal: the chip survived a controller
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
     * ( explicitly names "orphaned" as a `cancelled` cause)
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
        fun fromWireStatus(status: String?): SubagentChipState = when (val raw = status?.trim()?.lowercase()) {
            null, "" -> OBSERVED
            // Kept ahead of the shared vocabulary: this state machine draws a
            // distinction the wire vocabulary does not, between "dispatched but
            // not yet started" (OBSERVED) and RUNNING.
            in SubagentStatus.PENDING_ALIASES -> OBSERVED
            "orphaned", "unknown" -> ORPHANED
            else -> when (SubagentStatus.normalize(raw)) {
                SubagentStatus.RUNNING -> RUNNING
                SubagentStatus.COMPLETED -> COMPLETED
                SubagentStatus.FAILED -> FAILED
                SubagentStatus.CANCELLED -> CANCELLED
                else -> OBSERVED
            }
        }
    }
}

/**
 * WHICH producer a chip fact came from, and the total
 * order between competing producers.
 *
 * The same chip can be described by three independent producers at once, and
 * before this bead the last writer won — which is how local-runtime mode ended
 * up rendering a frozen SHIM snapshot that no live producer could ever clear.
 * Precedence is now an explicit, total, documented order:
 *
 * | source | precedence | authority |
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
    val activity: SubagentActivitySnapshot? = null,
    /**
     * Controller connection generation that last touched this chip. Data, not
     * identity: reconciliation uses it to tell "seen on the current connection"
     * from "rehydrated from a previous process".
     */
    val generation: Long = 0,
    @SerialName("first_seen_epoch_ms") val firstSeenEpochMs: Long = 0,
    @SerialName("last_seen_epoch_ms") val lastSeenEpochMs: Long = 0,
    @SerialName("terminal_at_epoch_ms") val terminalAtEpochMs: Long? = null,
    /**
     * Why the host, not the App Server, ended this chip.
     * [TERMINAL_REASON_STALE] marks a RUNNING chip that went unobserved past
     * [DurableSubagentRegistry.STALE_RUNNING_AFTER_MS]. Null for every
     * App-Server-reported terminal and for reconcile orphaning (its state,
     * ORPHANED, already says why). Older readers ignore the field.
     */
    @SerialName("terminal_reason") val terminalReason: String? = null,
    /**  (RC5): native progress; see [SubagentProgress]. */
    val progress: SubagentProgress = SubagentProgress(),
    /**
     *  (RC4): when this chip last made progress (first seen,
     * a new tool call, new activity, or a lifecycle change). Unlike
     * [lastSeenEpochMs] a repeated identical snapshot does not move it, so it
     * is the honest baseline for "quiet for a while".
     */
    @SerialName("last_activity_epoch_ms") val lastActivityEpochMs: Long? = null,
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
        activity = activity,
        terminalAtEpochMs = terminalAtEpochMs,
        toolCallCount = progress.toolCallCount,
        lastActivityAtMs = lastActivityEpochMs,
        model = progress.model,
        totalTokens = progress.totalTokens,
        durationMs = progress.durationMs,
        isBackground = progress.isBackground,
    )

    companion object {
        /** The host's TTL terminalized an unobserved RUNNING chip. */
        const val TERMINAL_REASON_STALE = "stale"
    }
}

/**
 *  (RC5): letta-code's native per-subagent progress, kept on
 * the durable chip so pushes and `subagent.list` carry it. Every field is
 * optional; a producer that does not know a value leaves the stored one.
 */
@Serializable
data class SubagentProgress(
    @SerialName("tool_call_count") val toolCallCount: Int? = null,
    val model: String? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("is_background") val isBackground: Boolean? = null,
) {
    /** [incoming] wins where it knows a value; the tool-call count never goes backwards. */
    fun mergedWith(incoming: SubagentProgress): SubagentProgress = SubagentProgress(
        toolCallCount = listOfNotNull(toolCallCount, incoming.toolCallCount).maxOrNull(),
        model = incoming.model ?: model,
        totalTokens = incoming.totalTokens ?: totalTokens,
        durationMs = incoming.durationMs ?: durationMs,
        isBackground = incoming.isBackground ?: isBackground,
    )

    /** True when [incoming] shows more work than this record has seen. */
    fun advancedBy(incoming: SubagentProgress): Boolean =
        (incoming.toolCallCount ?: 0) > (toolCallCount ?: 0)

    companion object {
        fun of(entry: SubagentEntry): SubagentProgress = SubagentProgress(
            toolCallCount = entry.toolCallCount,
            model = entry.model,
            totalTokens = entry.totalTokens,
            durationMs = entry.durationMs,
            isBackground = entry.isBackground,
        )
    }
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
 *    (one process-wide slot is the defect class).
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
    val activity: SubagentActivitySnapshot? = null,
    val generation: Long = 0,
    val progress: SubagentProgress = SubagentProgress(),
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
            activity = entry.activity,
            generation = generation,
            progress = SubagentProgress.of(entry),
        )
    }
}
