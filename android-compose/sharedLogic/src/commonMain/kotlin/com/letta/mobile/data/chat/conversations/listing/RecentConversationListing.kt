package com.letta.mobile.data.chat.conversations.listing

import com.letta.mobile.data.chat.runtime.ChatGateway
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

/** Session-local recents plus explicitly requested agent pages; legacy hosts retain their eager policy. */
internal class RecentConversationListing(private val gateway: ChatGateway) {
    private val mutex = Mutex()
    private val fleet = AgentConversationFleet(gateway.agentConversationReader())
    private val legacy = AgentConversationFleet(
        gateway.agentConversationReader(), AgentConversationFleetPaging(eagerPagesPerAgent = READ_THROUGH_PAGES),
    )
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

    suspend fun refresh(roster: List<Agent>, pins: Set<AgentId>, publish: (List<Conversation>) -> Unit) = mutex.withLock {
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

    suspend fun loadMore(agent: AgentId): List<Conversation> = mutex.withLock {
        if (!legacyMode) fleet.loadMore(agent)
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
            runCatchingCancellable { readAllAgentPages(id) }.onFailure { failure ->
                failures[id] = failure
                Telemetry.event(
                    "Conversations", "list.agent_read_failed", "agentId" attr id.value, "reason" attr failure.message,
                )
            }
        }
        searchFailures = failures
        if (failures.size == agents.size) failures.values.firstOrNull()?.let { throw it }
    }

    suspend fun changed(dirty: Map<ConversationId, AgentId?>, roster: List<Agent>): List<Conversation> = mutex.withLock {
        if (legacyMode) return@withLock legacy.refreshForInvalidations(dirty, { roster }) {
            gateway.getConversation(it.value)
        }.first
        val refresh = mutableSetOf<AgentId>()
        for ((id, hintedOwner) in dirty) {
            val owner = hintedOwner ?: conversations.firstOrNull { it.id == id }?.agentId
            if (owner in fleet.loadedAgents) {
                // The dirty row may be in a cached older page, outside the refreshed first page.
                val row = gateway.getConversation(id.value)
                if (row.agentId in fleet.loadedAgents) fleet.record(row)
                refresh += requireNotNull(owner)
            } else {
                reconcileUnloadedRow(id, roster, refresh)
            }
        }
        fleet.refreshAgents(refresh)
        recents = recents.filterNot { it.agentId in refresh }
        conversations
    }

    private suspend fun readAllAgentPages(id: AgentId) {
        if (id !in fleet.loadedAgents) fleet.refreshAgents(setOf(id))
        while (fleet.hasMore(id)) fleet.loadMore(id)
    }

    private suspend fun reconcileUnloadedRow(id: ConversationId, roster: List<Agent>, refresh: MutableSet<AgentId>) {
        val row = gateway.getConversation(id.value)
        if (row.agentId in fleet.loadedAgents) {
            refresh += row.agentId
            return
        }
        val hidden = isHiddenOwner(row.agentId, roster)
        val shown = listOf(row).visibleConversationsInLists().filterNot { it.archived == true || hidden }
        recents = (shown + recents.filterNot { it.id == id })
            .sortedWith(ConversationRecency.newestFirst).take(RECENTS_LIMIT)
    }

    private companion object {
        const val RECENTS_LIMIT = 30
    }
}

private fun hiddenAgentsIn(roster: List<Agent>): Set<AgentId> =
    roster.mapTo(mutableSetOf()) { it.id } - roster.visibleInLists().mapTo(mutableSetOf()) { it.id }

private fun isHiddenOwner(owner: AgentId, roster: List<Agent>): Boolean {
    val known = roster.firstOrNull { it.id == owner } ?: return false
    return listOf(known).visibleInLists().isEmpty()
}
