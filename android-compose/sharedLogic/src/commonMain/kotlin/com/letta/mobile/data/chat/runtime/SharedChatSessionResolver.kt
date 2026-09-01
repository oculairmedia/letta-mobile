package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IConversationRepository
import com.letta.mobile.data.timeline.TimelineCandidateListFreshness
import com.letta.mobile.data.timeline.TimelineCandidateListSource
import com.letta.mobile.data.timeline.TimelineConversationAttribution
import com.letta.mobile.data.timeline.TimelineConversationAttributionCapture
import com.letta.mobile.data.timeline.TimelineConversationSelectionMode
import com.letta.mobile.data.timeline.TimelineProvenanceRedaction
import com.letta.mobile.data.timeline.classifyConversationAttribution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Platform-neutral resolution of the agent + conversation a chat session should
 * open with. This is the conversation-lifecycle logic that both Android and
 * desktop need at session start:
 *
 *  - resolving an agent's display name from the cache,
 *  - observing the cached name reactively, and
 *  - picking the most-recent real conversation to resume (skipping the
 *    default-shim placeholder), refreshing a stale cache as needed.
 *
 * It depends only on the shared [IAgentRepository] / [IConversationRepository]
 * contracts (already in commonMain) and kotlinx.coroutines, so it carries no
 * Android lifecycle, Dagger, or concrete-repository assumptions. Platform code
 * is expected to own only the wiring (DI, scopes) and to delegate the actual
 * resolution here.
 */
class SharedChatSessionResolver(
    private val agentRepository: IAgentRepository,
    private val conversationRepository: IConversationRepository,
    private val backgroundRefreshScope: CoroutineScope? = null,
) {
    fun cachedAgentName(agentId: String): String? {
        return agentRepository.getCachedAgent(AgentId(agentId))
            ?.name
            ?.takeIf { it.isNotBlank() }
    }

    fun observeCachedAgentName(agentId: String): Flow<String> {
        return agentRepository.agents
            .map { agents -> agents.firstOrNull { it.id.value == agentId }?.name.orEmpty() }
            .distinctUntilChanged()
    }

    suspend fun resolveMostRecentConversation(
        agentId: String,
        maxAgeMs: Long,
    ): String? = resolveMostRecentConversationWithProvenance(agentId, maxAgeMs).conversationId

    /**
     * letta-mobile-grrhq: the SAME selection as [resolveMostRecentConversation],
     * additionally returning a bounded, redacted attribution capture of the
     * candidates considered.
     *
     * Behaviour-neutral by construction: [resolveMostRecentConversation]
     * delegates here, so exactly one selection implementation exists and the
     * capture cannot drift from the choice it describes. Nothing in the capture
     * is read to make the selection.
     *
     * [parentAgentId] is optional. When it is null the attribution class is
     * reported as UNKNOWN rather than guessed — see
     * [classifyConversationAttribution] for why "requested-only" must not be
     * asserted without a known other claimant.
     */
    suspend fun resolveMostRecentConversationWithProvenance(
        agentId: String,
        maxAgeMs: Long,
        parentAgentId: String? = null,
    ): ConversationSelection {
        // Call ordering below mirrors the pre-instrumentation implementation
        // EXACTLY — same number of getCachedConversations reads, and
        // hasFreshConversations still consulted only when a cached conversation
        // exists. The candidate list is captured from the SAME read the
        // selection was made from, so the capture cannot describe a different
        // list than the one that produced the choice.
        var wasFresh = false
        var refreshed = false
        var candidates = conversationRepository.getCachedConversations(AgentId(agentId))
        var selected = mostRecentConversationIdIn(candidates)
        if (selected != null) {
            wasFresh = conversationRepository.hasFreshConversations(AgentId(agentId), maxAgeMs)
            if (!wasFresh) {
                if (backgroundRefreshScope != null) {
                    backgroundRefreshScope.launch {
                        runCatching { conversationRepository.refreshConversationsIfStale(AgentId(agentId), maxAgeMs) }
                    }
                } else {
                    conversationRepository.refreshConversationsIfStale(AgentId(agentId), maxAgeMs)
                    refreshed = true
                }
            }
        } else {
            conversationRepository.refreshConversationsIfStale(AgentId(agentId), maxAgeMs)
            refreshed = true
            candidates = conversationRepository.getCachedConversations(AgentId(agentId))
            selected = mostRecentConversationIdIn(candidates)
        }
        return ConversationSelection(
            conversationId = selected,
            capture = buildCapture(
                requestedAgentId = agentId,
                selectedConversationId = selected,
                candidates = candidates,
                parentAgentId = parentAgentId,
                selectionMode = TimelineConversationSelectionMode.MOST_RECENT_FALLBACK,
                freshness = when {
                    refreshed -> TimelineCandidateListFreshness.REFRESHED
                    wasFresh -> TimelineCandidateListFreshness.FRESH
                    else -> TimelineCandidateListFreshness.STALE
                },
            ),
        )
    }

    /**
     * Build the bounded attribution capture for a selection. Public so callers
     * that chose a conversation by a NON-resolver route (explicit id, route
     * state) can emit the same shape and stay comparable in the log.
     */
    fun captureAttribution(
        requestedAgentId: String,
        selectedConversationId: String?,
        parentAgentId: String?,
        selectionMode: TimelineConversationSelectionMode,
        freshness: TimelineCandidateListFreshness = TimelineCandidateListFreshness.UNKNOWN,
    ): TimelineConversationAttributionCapture = buildCapture(
        requestedAgentId = requestedAgentId,
        selectedConversationId = selectedConversationId,
        candidates = conversationRepository.getCachedConversations(AgentId(requestedAgentId)),
        parentAgentId = parentAgentId,
        selectionMode = selectionMode,
        freshness = freshness,
    )

    private fun buildCapture(
        requestedAgentId: String,
        selectedConversationId: String?,
        candidates: List<com.letta.mobile.data.model.Conversation>,
        parentAgentId: String?,
        selectionMode: TimelineConversationSelectionMode,
        freshness: TimelineCandidateListFreshness,
    ): TimelineConversationAttributionCapture {
        val candidateIds = candidates.map { it.id.value }
        val parentCandidateIds = parentAgentId
            ?.let { parent -> conversationRepository.getCachedConversations(AgentId(parent)).map { it.id.value } }
            .orEmpty()
        val selectedRecordAgentId = candidates.firstOrNull { it.id.value == selectedConversationId }?.agentId?.value
        val attribution = classifyConversationAttribution(
            selectedConversationId = selectedConversationId,
            requestedAgentId = requestedAgentId,
            selectedRecordAgentId = selectedRecordAgentId,
            requestedAgentCandidateIds = candidateIds.toSet(),
            parentAgentId = parentAgentId,
            parentAgentCandidateIds = parentCandidateIds.toSet(),
        )
        return TimelineConversationAttributionCapture(
            requestedAgentId = requestedAgentId,
            selectedConversationId = selectedConversationId,
            selectedRecordAgentId = selectedRecordAgentId,
            candidateCount = candidateIds.size,
            parentAgentId = parentAgentId,
            selectedAlsoAttributedToParent = attribution == TimelineConversationAttribution.BOTH ||
                attribution == TimelineConversationAttribution.PARENT_AGENT_ONLY,
            attribution = attribution,
            selectionMode = selectionMode,
            candidateListSource = TimelineCandidateListSource.AGENT_SCOPED_CACHE,
            candidateListFreshness = freshness,
            candidateIdSample = TimelineProvenanceRedaction.boundedSample(candidateIds),
            candidateIdDigest = TimelineProvenanceRedaction.setDigest(candidateIds),
        )
    }

    /** Result of a provenance-carrying conversation selection. */
    data class ConversationSelection(
        val conversationId: String?,
        val capture: TimelineConversationAttributionCapture,
    )

    /**
     * The selection rule, factored out over an already-fetched candidate list so
     * the attribution capture can describe exactly the list the choice was made
     * from. Behaviour is byte-for-byte the pre-instrumentation rule: skip the
     * default-shim placeholder, then take the newest by lastMessageAt/createdAt.
     */
    private fun mostRecentConversationIdIn(
        candidates: List<com.letta.mobile.data.model.Conversation>,
    ): String? = candidates
        .filterNot { it.id.value.startsWith(DEFAULT_SHIM_CONVERSATION_PREFIX) }
        .maxByOrNull { it.lastMessageAt ?: it.createdAt ?: "" }
        ?.id
        ?.value

    companion object {
        const val DEFAULT_SHIM_CONVERSATION_PREFIX = "conv-default-"
    }
}
