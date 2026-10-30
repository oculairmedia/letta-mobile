package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.conversations.listing.RecentConversationListing
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationRecency
import com.letta.mobile.data.model.visibleInLists
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** Demand loading and bounded reconciliation, separate from the owner's chat and management actions. */
class TimelineConversationLists internal constructor(
    gateway: ChatGateway,
    private val state: MutableStateFlow<TimelineConversationsState>,
    private val roster: () -> List<Agent>,
    private val pins: () -> Set<AgentId>,
    private val setPins: (Set<AgentId>) -> Unit,
) {
    private val listing = RecentConversationListing(gateway)
    private val operations = Mutex()
    private val bootstrapClock = TimeSource.Monotonic.markNow()
    private var bootstrapStarted = false
    private var firstRowsPublished = false
    private var bootstrapComplete = false
    internal val legacyMode: Boolean get() = listing.legacyMode

    /** Recents, pins and cached agents only; unsupported hosts retain the legacy fleet refresh. */
    suspend fun refresh() {
        operations.withLock { bootstrap() }
    }

    private suspend fun bootstrap() {
        if (!bootstrapStarted) {
            bootstrapStarted = true
            bootstrapEvent("list.bootstrap_start", 0)
        }
        runCatchingCancellable { listing.refresh(roster(), pins(), ::publishRows) }
            .onSuccess { rows ->
                publishRows(rows)
                val settled = !listing.legacyMode || roster().isNotEmpty() || state.value.loaded
                state.update { it.copy(loaded = settled, authoritative = listing.legacyMode && settled, error = null) }
                completeBootstrap(settled)
            }
            .onFailure { failure -> state.update { it.copy(error = failure.message ?: LIST_FAILED) } }
    }

    private fun completeBootstrap(settled: Boolean) {
        if (!settled || bootstrapComplete) return
        if (!firstRowsPublished) {
            firstRowsPublished = true
            bootstrapEvent("list.first_rows_published", state.value.conversations.size)
        }
        bootstrapComplete = true
        bootstrapEvent("list.bootstrap_complete", state.value.conversations.size)
    }

    /** Loads the first page only for this agent, once per session. */
    suspend fun focusAgent(agent: AgentId) {
        if (!isVisibleAgent(agent)) return
        updateListing { listing.focus(agent) }
    }

    private fun isVisibleAgent(agent: AgentId): Boolean = roster().visibleInLists().any { it.id == agent }

    /** Reads another page only for this agent. */
    suspend fun loadMore(agent: AgentId) {
        if (!isVisibleAgent(agent)) return
        updateListing { listing.loadMore(agent) }
    }

    /** Newly pinned visible agents get only their first page, without another recents read. */
    suspend fun prefetchPinnedAgents(agents: Set<AgentId>) {
        setPins(agents)
        if (!state.value.loaded) return
        val visible = roster().visibleInLists().mapTo(mutableSetOf()) { it.id }
        agents.intersect(visible).forEach { focusAgent(it) }
    }

    /** Whether this agent still has unread pages. */
    fun hasMore(agent: AgentId): Boolean = isVisibleAgent(agent) && listing.hasMore(agent)

    /** Explicit search demand reads unvisited agents; null scope searches the visible roster. */
    suspend fun prepareSearch(agent: AgentId? = null) = operations.withLock {
        val result = runCatchingCancellable { listing.search(roster(), agent) }
        publishRows(result.getOrElse { listing.conversations })
        val failure = listing.searchFailures.values.firstOrNull() ?: result.exceptionOrNull()
        val message = failure?.let { it.message ?: LIST_FAILED }
        state.update {
            it.copy(searchFailedAgents = listing.searchFailures.keys.toSet(), searchError = message, error = message)
        }
    }

    internal suspend fun rosterChanged() {
        if (legacyMode) refresh() else {
            updateListing { listing.rosterChanged(roster()) }
            prefetchPinnedAgents(pins())
        }
    }

    internal suspend fun changed(dirty: Map<ConversationId, AgentId?>) = updateListing { listing.changed(dirty, roster()) }

    private suspend fun updateListing(read: suspend () -> List<Conversation>) {
        operations.withLock {
            runCatchingCancellable { read() }.onSuccess { rows ->
                publishRows(rows)
                state.update { it.copy(error = null) }
            }.onFailure { failure -> state.update { it.copy(error = failure.message ?: LIST_FAILED) } }
        }
    }

    private fun publishRows(rows: List<Conversation>) {
        state.update { current ->
            val selected = current.conversations.filter { it.id == current.selected }
            val merged = (rows + selected).distinctBy { it.id }.sortedWith(ConversationRecency.newestFirst)
            current.copy(conversations = merged, authoritativeAgents = listing.authoritativeAgents)
        }
        val count = state.value.conversations.size
        if (count > 0 && !firstRowsPublished) {
            firstRowsPublished = true
            bootstrapEvent("list.first_rows_published", count)
        }
    }

    private fun bootstrapEvent(event: String, count: Int) {
        Telemetry.event(
            "Conversations", event, "rowCount" attr count,
            "elapsedMs" attr bootstrapClock.elapsedNow().inWholeMilliseconds,
        )
    }

    private companion object {
        const val LIST_FAILED = "Could not list the conversations"
    }
}
