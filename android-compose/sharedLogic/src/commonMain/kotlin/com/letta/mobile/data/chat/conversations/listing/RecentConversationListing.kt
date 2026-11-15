package com.letta.mobile.data.chat.conversations.listing

import com.letta.mobile.data.backend.ConversationListing
import com.letta.mobile.data.backend.ConversationListingFactory
import com.letta.mobile.data.chat.runtime.ChatConversationStore
import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.chat.runtime.agentConversationReader
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationRecency
import com.letta.mobile.data.model.visibleConversationsInLists
import com.letta.mobile.data.model.visibleInLists
import com.letta.mobile.util.AgentConversationFleet
import com.letta.mobile.util.AgentConversationFleetPaging
import com.letta.mobile.util.READ_THROUGH_PAGES
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import com.letta.mobile.util.runCatchingCancellable
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Adapts the repository's existing read paths to the shared listing without coupling packages. */
private class RepositoryConversationStore(
    private val listAgent: suspend (AgentConversationPage) -> List<Conversation>,
    private val listRecents: suspend (Int, ConversationId?) -> List<Conversation>,
    private val getConversation: suspend (ConversationId) -> Conversation,
) : ChatConversationStore {
    override suspend fun listAgentConversations(page: AgentConversationPage): List<Conversation> = listAgent(page)

    override suspend fun listRecentConversations(limit: Int, after: ConversationId?): List<Conversation> =
        listRecents(limit, after)

    override suspend fun getConversation(id: ConversationId): Conversation = getConversation.invoke(id)
}

/**
 * Session-local recents plus explicitly requested agent pages; legacy hosts retain their eager policy.
 * Every conversation list (web and desktop timelines, Android's fleet list) reads through this one listing.
 */
internal class RecentConversationListing(
    private val gateway: ChatConversationStore,
    /** How a host without visible recents is read agent by agent; the caller keeps its own legacy paging. */
    legacyPaging: AgentConversationFleetPaging = AgentConversationFleetPaging(eagerPagesPerAgent = READ_THROUGH_PAGES),
) : ConversationListing {
    private val mutex = Mutex()
    private val fleet = AgentConversationFleet(gateway.agentConversationReader())
    private val legacy = AgentConversationFleet(gateway.agentConversationReader(), legacyPaging)
    private var recents: List<Conversation> = emptyList()
    private var knownHiddenAgents: Set<AgentId> = emptySet()
    var searchFailures: Map<AgentId, Throwable> = emptyMap()
        private set
    var legacyMode: Boolean = false
        private set

    val conversations: List<Conversation>
        get() = if (legacyMode) legacy.conversations else (recents + fleet.conversations)
            .distinctBy { it.id }.sortedWith(ConversationRecency.newestFirst)

    val authoritativeAgents: Set<AgentId> get() = if (legacyMode) emptySet() else fleet.authoritativeAgents

    fun hasMore(agent: AgentId): Boolean = !legacyMode && fleet.hasMore(agent)

    /** Some listed agent (any agent on a legacy host) has pages not read yet; recents alone never page. */
    override val hasMore: Boolean get() = if (legacyMode) legacy.hasMore else fleet.hasMore

    /**
     * Rows already re-read one by one (a push's `conversation.get`), folded in without listing any
     * agent: a loaded agent's row joins its pages, any other visible row joins the recents.
     */
    override suspend fun record(rows: List<Conversation>, roster: List<Agent>): List<Conversation> = mutex.withLock {
        for (row in rows) {
            when {
                legacyMode -> legacy.record(row)
                row.agentId in fleet.loadedAgents -> fleet.record(row)
                else -> mergeRecent(row, roster)
            }
        }
        conversations
    }

    override suspend fun refresh(roster: List<Agent>, pins: Set<AgentId>, publish: (List<Conversation>) -> Unit) = mutex.withLock {
        knownHiddenAgents = knownHiddenAgents + hiddenAgentsIn(roster)
        if (!legacyMode) refreshRecents()
        if (legacyMode) {
            legacy.refresh(roster)
        } else {
            publish(conversations)
            val visible = roster.visibleInLists().mapTo(mutableSetOf()) { it.id }
            fleet.refreshAgents((fleet.loadedAgents + pins).intersect(visible))
            // Agent pages are authoritative for those owners; do not retain their stale recents rows.
            recents = recents.filterNot { it.agentId in fleet.loadedAgents }
        }
        conversations
    }

    private suspend fun refreshRecents() {
        try {
            recents = gateway.listRecentConversations()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unsupported: UnsupportedOperationException) {
            fallBackToLegacy(unsupported.message)
        } catch (failure: TimelineTransportHttpException) {
            fallBackOrRethrow(failure)
        }
    }

    /** The host lacks visible recents (capability_unavailable); any other failure is a real one. */
    private fun fallBackOrRethrow(failure: TimelineTransportHttpException) {
        if (failure.message?.contains("capability_unavailable") != true) throw failure
        fallBackToLegacy(failure.message)
    }

    private fun fallBackToLegacy(reason: String?) {
        legacyMode = true
        Telemetry.event("Conversations", "list.legacy_fallback", "reason" attr reason.orEmpty())
    }

    suspend fun rosterChanged(roster: List<Agent>): List<Conversation> = mutex.withLock {
        val visible = roster.visibleInLists().mapTo(mutableSetOf()) { it.id }
        val hidden = hiddenAgentsIn(roster)
        val restored = knownHiddenAgents.intersect(visible)
        // An arriving directory is not demand to read every agent; only visibility restoration is.
        fleet.retainAgents(fleet.loadedAgents - hidden)
        recents = recents.filterNot { it.agentId in hidden }
        fleet.refreshAgents(restored)
        recents = recents.filterNot { it.agentId in restored }
        knownHiddenAgents = (knownHiddenAgents + hidden) - restored
        conversations
    }

    suspend fun focus(agent: AgentId): List<Conversation> = mutex.withLock {
        if (!legacyMode && agent !in fleet.loadedAgents) {
            fleet.refreshAgents(setOf(agent))
            recents = recents.filterNot { it.agentId == agent }
        }
        conversations
    }

    /** The next page of just [agent], or of every listed agent that has one; never reads an unlisted agent. */
    override suspend fun loadMore(agent: AgentId?): List<Conversation> = mutex.withLock {
        when {
            agent != null -> if (!legacyMode) fleet.loadMore(agent)
            legacyMode -> legacy.loadMore()
            else -> fleet.loadMore()
        }
        conversations
    }

    /** Search is the explicit user demand that permits reading previously unvisited agents. */
    suspend fun search(roster: List<Agent>, agent: AgentId?): List<Conversation> = mutex.withLock {
        searchFailures = emptyMap()
        if (!legacyMode) {
            readSearchAgents(roster.visibleInLists().map { it.id }.filter { agent == null || it == agent })
            recents = recents.filterNot { it.agentId in fleet.loadedAgents }
        }
        conversations
    }

    private suspend fun readSearchAgents(agents: List<AgentId>) {
        val failures = mutableMapOf<AgentId, Throwable>()
        for (id in agents) {
            runCatchingCancellable { fleet.readAllAgentPages(id) }.onFailure { failure ->
                failures[id] = failure
                Telemetry.event(
                    "Conversations", "list.agent_read_failed", "agentId" attr id.value, "reason" attr failure.message,
                )
            }
        }
        searchFailures = failures
        if (failures.size == agents.size) failures.values.firstOrNull()?.let { throw it }
    }

    override suspend fun changed(dirty: Map<ConversationId, AgentId?>, roster: List<Agent>): List<Conversation> = mutex.withLock {
        if (legacyMode) return@withLock legacy.refreshForInvalidations(dirty, { roster }) {
            gateway.getConversation(it.value)
        }.first
        val refresh = mutableSetOf<AgentId>()
        for ((id, hintedOwner) in dirty) {
            val owner = hintedOwner ?: conversations.firstOrNull { it.id == id }?.agentId
            dirtyRowRefreshTarget(id, owner, roster)?.let { refresh += it }
        }
        fleet.refreshAgents(refresh)
        recents = recents.filterNot { it.agentId in refresh }
        conversations
    }

    /**
     * Re-reads one dirty row and returns the loaded agent to refresh for it, or null when it joined the recents.
     * A loaded owner's row may be in a cached older page, outside the refreshed first page, so it is recorded;
     * an unloaded owner may have loaded meanwhile, else its row joins the recents.
     */
    private suspend fun dirtyRowRefreshTarget(id: ConversationId, owner: AgentId?, roster: List<Agent>): AgentId? {
        val row = gateway.getConversation(id.value)
        val ownerLoaded = owner != null && owner in fleet.loadedAgents
        val rowLoaded = row.agentId in fleet.loadedAgents
        if (ownerLoaded && rowLoaded) fleet.record(row)
        return when {
            ownerLoaded -> owner
            rowLoaded -> row.agentId
            else -> {
                mergeRecent(row, roster)
                null
            }
        }
    }

    /** A row of an unloaded agent replaces its recents entry; archived or hidden rows leave the recents. */
    private fun mergeRecent(row: Conversation, roster: List<Agent>) {
        val hidden = isHiddenOwner(row.agentId, roster)
        val shown = listOf(row).visibleConversationsInLists().filterNot { it.archived == true || hidden }
        recents = (shown + recents.filterNot { it.id == row.id })
            .sortedWith(ConversationRecency.newestFirst).take(RECENTS_LIMIT)
    }

    private companion object {
        const val RECENTS_LIMIT = 30
    }
}

private suspend fun AgentConversationFleet.readAllAgentPages(id: AgentId) {
    if (id !in loadedAgents) refreshAgents(setOf(id))
    while (hasMore(id)) loadMore(id)
}

private fun hiddenAgentsIn(roster: List<Agent>): Set<AgentId> =
    roster.mapTo(mutableSetOf()) { it.id } - roster.visibleInLists().mapTo(mutableSetOf()) { it.id }

private fun isHiddenOwner(owner: AgentId, roster: List<Agent>): Boolean {
    val known = roster.firstOrNull { it.id == owner } ?: return false
    return listOf(known).visibleInLists().isEmpty()
}

/** The shared listing with the cached repository's existing one-page-per-agent legacy policy. */
fun repositoryConversationListingFactory(): ConversationListingFactory = ConversationListingFactory {
        listAgent, listRecents, getConversation ->
    RecentConversationListing(
        RepositoryConversationStore(
            listAgent = { page -> listAgent(page.agent, page.after, page.limit) },
            listRecents = listRecents,
            getConversation = getConversation,
        ),
        AgentConversationFleetPaging(),
    )
}
