package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus

/**
 * letta-mobile-5172y.1: PURE commonMain model that groups ephemeral
 * "Letta Code" subagent conversations by AUTHORITATIVE parent provenance.
 *
 * Consumed by BOTH mobile and desktop (later beads). No UI, no coroutines,
 * no java.*, no platform APIs — just a pure function + data types so it is
 * gated by :sharedLogic:allTests (jvm + android-unit + hostNative).
 *
 * Sibling precedent: [groupConversationsByAgentName] in
 * ChatRuntimeContracts.kt groups the SAME [ChatConversationSummary] type by
 * DISPLAY NAME. This grouping instead keys off the shim-emitted parent
 * identity carried on [SubagentEntry] ([SubagentEntry.parentConversationId] /
 * [SubagentEntry.parentAgentId] / [SubagentEntry.parentRunId]) so unrelated
 * subagents that happen to share a display name are NEVER merged.
 *
 * GRACEFUL DEGRADATION: live subagent identity
 * ([SubagentEntry.subagentConversationId] / [SubagentEntry.subagentAgentId])
 * may be null today (filled later by m6oa1.3 PR-B). When NO entry matches any
 * conversation, the function returns zero stacks and every conversation as
 * ungrouped — it never crashes and never guesses.
 */

/**
 * Authoritative parent provenance of a Letta Code subagent conversation.
 *
 * The grouping key is derived from parent identity ONLY — never from the
 * display name — so two subagents with the same name but different parents
 * stay in distinct stacks.
 */
sealed interface SubagentProvenance {
    /**
     * At least one of the parent identity fields was present on the matching
     * [SubagentEntry]. Same-provenance entries collapse into one stack.
     */
    data class Known(
        val parentAgentId: String?,
        val parentConversationId: String?,
        val parentRunId: String?,
    ) : SubagentProvenance

    /**
     * No parent identity was available. Rendered as [UNKNOWN_ORIGIN_LABEL].
     * Unknown-provenance conversations are NOT merged with each other — each
     * stays keyed by its own conversation identity so unrelated unknowns are
     * never falsely combined.
     */
    data object Unknown : SubagentProvenance

    companion object {
        const val UNKNOWN_ORIGIN_LABEL = "Unknown origin"
    }
}

/**
 * One authoritative "stack" of subagent conversations that share a parent
 * provenance (or, for [SubagentProvenance.Unknown], one distinct conversation
 * that could not be attributed to a parent).
 *
 * Field-availability notes (origin/main [ChatConversationSummary] shape):
 *  - [representativeConversationId] uses INPUT LIST ORDER as the recency
 *    signal: [ChatConversationSummary] exposes only [updatedAtLabel], a
 *    pre-formatted DISPLAY string (e.g. "Remote"/ISO/relative) that is not a
 *    reliable sortable timestamp. The representative is therefore the FIRST
 *    member in input order — callers that have a real recency signal should
 *    pre-sort the conversation list.
 *  - No `pinned`/`active` flag is derivable: [ChatConversationSummary]
 *    exposes no such field (only `archived` / `unreadCount`), so none is
 *    surfaced here. Only [running] — derivable from member entry status — is
 *    exposed.
 */
data class SubagentStack(
    val stackKey: String,
    val provenance: SubagentProvenance,
    val memberConversationIds: List<String>,
    val count: Int,
    val representativeConversationId: String,
    val running: Boolean,
)

/**
 * Result of [groupSubagentConversations]: subagent conversations collapsed
 * into authoritative [stacks], and everything else passed through as
 * [ungrouped] (input order preserved).
 */
data class SubagentConversationGrouping(
    val stacks: List<SubagentStack>,
    val ungrouped: List<ChatConversationSummary>,
)

/**
 * Group ephemeral Letta Code subagent conversations by authoritative parent
 * provenance.
 *
 * JOIN: a conversation `C` is a Letta Code subagent conversation iff some
 * [SubagentEntry] has `subagentConversationId == C.id` OR (fallback)
 * `subagentAgentId == C.agentId`. conversationId is the stronger key and wins
 * when both could match. Non-matching conversations are returned untouched in
 * [SubagentConversationGrouping.ungrouped].
 *
 * PROVENANCE KEY (grouping key):
 * `parentConversationId ?: parentAgentId ?: parentRunId`. NEVER the display
 * name. When all three are null the provenance is [SubagentProvenance.Unknown]
 * and the conversation is bucketed by its OWN identity
 * (`C.id`) so unrelated unknowns are not collapsed together.
 *
 * Deterministic: stable keys, stable ordering (Known stacks first, ordered by
 * first appearance of their provenance key in the conversation list; then
 * Unknown stacks in conversation order). [SubagentConversationGrouping.ungrouped]
 * preserves input order.
 */
fun groupSubagentConversations(
    conversations: List<ChatConversationSummary>,
    entries: List<SubagentEntry>,
): SubagentConversationGrouping {
    // Index the active-subagent snapshot by its two identity keys. The first
    // entry for a given key wins so the join is deterministic when the shim
    // reports duplicates. conversationId is the stronger key.
    val entryByConversationId = LinkedHashMap<String, SubagentEntry>()
    val entryByAgentId = LinkedHashMap<String, SubagentEntry>()
    for (entry in entries) {
        entry.subagentConversationId
            ?.takeIf { it.isNotBlank() }
            ?.let { key -> if (!entryByConversationId.containsKey(key)) entryByConversationId[key] = entry }
        entry.subagentAgentId
            ?.takeIf { it.isNotBlank() }
            ?.let { key -> if (!entryByAgentId.containsKey(key)) entryByAgentId[key] = entry }
    }

    // Preserve deterministic stack ordering by first appearance of the stack
    // key while conversations are walked in input order.
    val stackOrder = LinkedHashMap<String, MutableStackAccumulator>()
    val ungrouped = ArrayList<ChatConversationSummary>()

    for (conversation in conversations) {
        val matched = matchEntry(conversation, entryByConversationId, entryByAgentId)
        if (matched == null) {
            ungrouped.add(conversation)
            continue
        }

        val provenanceKey = matched.provenanceKey()
        val stackKey: String
        val provenance: SubagentProvenance
        if (provenanceKey == null) {
            // Unknown provenance: bucket by the conversation's own identity so
            // unrelated unknowns never merge into one authoritative stack.
            stackKey = UNKNOWN_STACK_KEY_PREFIX + conversation.id
            provenance = SubagentProvenance.Unknown
        } else {
            stackKey = KNOWN_STACK_KEY_PREFIX + provenanceKey
            provenance = SubagentProvenance.Known(
                parentAgentId = matched.parentAgentId,
                parentConversationId = matched.parentConversationId,
                parentRunId = matched.parentRunId,
            )
        }

        val accumulator = stackOrder.getOrPut(stackKey) {
            MutableStackAccumulator(stackKey = stackKey, provenance = provenance)
        }
        accumulator.add(conversation, matched)
    }

    val stacks = stackOrder.values.map { it.toStack() }
    return SubagentConversationGrouping(stacks = stacks, ungrouped = ungrouped)
}

/**
 * conversationId is the stronger key: prefer a match on
 * `subagentConversationId == C.id`, then fall back to
 * `subagentAgentId == C.agentId`.
 */
private fun matchEntry(
    conversation: ChatConversationSummary,
    entryByConversationId: Map<String, SubagentEntry>,
    entryByAgentId: Map<String, SubagentEntry>,
): SubagentEntry? {
    entryByConversationId[conversation.id]?.let { return it }
    val agentId = conversation.agentId?.takeIf { it.isNotBlank() } ?: return null
    return entryByAgentId[agentId]
}

/**
 * PROVENANCE KEY: `parentConversationId ?: parentAgentId ?: parentRunId`.
 * Blank strings are treated as absent. Returns null when no parent identity
 * is available (→ [SubagentProvenance.Unknown]).
 */
private fun SubagentEntry.provenanceKey(): String? =
    parentConversationId?.takeIf { it.isNotBlank() }
        ?: parentAgentId?.takeIf { it.isNotBlank() }
        ?: parentRunId?.takeIf { it.isNotBlank() }

private class MutableStackAccumulator(
    private val stackKey: String,
    private val provenance: SubagentProvenance,
) {
    private val memberConversationIds = ArrayList<String>()
    private var representativeConversationId: String? = null
    private var running = false

    fun add(conversation: ChatConversationSummary, entry: SubagentEntry) {
        if (representativeConversationId == null) {
            // Recency signal: input list order (see [SubagentStack] docs).
            representativeConversationId = conversation.id
        }
        memberConversationIds.add(conversation.id)
        if (entry.status == SubagentStatus.RUNNING) {
            running = true
        }
    }

    fun toStack(): SubagentStack =
        SubagentStack(
            stackKey = stackKey,
            provenance = provenance,
            memberConversationIds = memberConversationIds.toList(),
            count = memberConversationIds.size,
            representativeConversationId = representativeConversationId
                ?: memberConversationIds.first(),
            running = running,
        )
}

// Namespaced stack-key prefixes keep Known and distinct-Unknown keys from
// ever colliding while remaining stable across runs.
private const val KNOWN_STACK_KEY_PREFIX = "known:"
private const val UNKNOWN_STACK_KEY_PREFIX = "unknown:"
