package com.letta.mobile.data.chat.conversations

import com.letta.mobile.data.chat.runtime.AgentConversationPage
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationRecency
import com.letta.mobile.data.model.visibleConversationsInLists
import com.letta.mobile.data.model.visibleInLists
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Reads one page of one agent's own conversations, newest first. */
fun interface AgentConversationReader {
    suspend fun read(page: AgentConversationPage): List<Conversation>
}

/** How a [AgentConversationFleet] pages: rows per read, reads at once, and how many pages a refresh reads eagerly per agent. */
data class AgentConversationFleetPaging(
    val pageSize: Int = AgentConversationPage.DEFAULT_LIMIT,
    val parallelism: Int = DEFAULT_PARALLELISM,
    /** 1 reads only the newest page (the host pages on with [AgentConversationFleet.loadMore]). */
    val eagerPagesPerAgent: Int = 1,
) {
    companion object {
        const val DEFAULT_PARALLELISM = 4

        /** A list with no "load more": each agent's conversations are read through, up to this many pages. */
        const val READ_THROUGH_PAGES = 10
    }
}

/**
 * Every conversation list's one reader, for every host (web, desktop, Android): the fleet's
 * conversations read agent by agent ([AgentConversationReader]), never as one window across every
 * agent, so a host's agent-free worker transcripts (most of its newest conversations) cannot
 * crowd a user's chats out. Only the roster's visible agents are read; each agent's rows are
 * its own, and the merged list is newest first ([ConversationRecency]). Each agent pages on from
 * its own cursor; one agent's failed read leaves the others listed, and only when every read
 * fails does the refresh fail.
 */
class AgentConversationFleet(
    private val reader: AgentConversationReader,
    private val paging: AgentConversationFleetPaging = AgentConversationFleetPaging(),
) {
    private val mutex = Mutex()
    private val permits = Semaphore(paging.parallelism)
    private var listed: Map<AgentId, AgentListing> = emptyMap()

    /** The merged list as last read. */
    val conversations: List<Conversation> get() = listed.merged()

    /** Some agent has conversations not read yet. */
    val hasMore: Boolean get() = listed.values.any { it.cursor != null }

    /** Reads [agents]' (the visible ones') newest conversations again, dropping every earlier page. */
    suspend fun refresh(agents: List<Agent>): List<Conversation> = mutex.withLock {
        val roster = agents.visibleInLists().map { it.id }.distinct()
        val read = readAll(roster.associateWith { AgentListing() }, paging.eagerPagesPerAgent)
        if (roster.isNotEmpty() && read.values.all { it.failure != null }) {
            throw read.values.first().failure ?: IllegalStateException(LIST_FAILED)
        }
        // An agent whose read failed keeps what it last listed, so one bad read does not empty its chats.
        listed = read.mapValues { (agent, listing) -> if (listing.failure != null) listed[agent] ?: listing else listing }
        listed.merged()
    }

    /** Reads the next page of every agent that has one; the list keeps what it had. */
    suspend fun loadMore(): List<Conversation> = mutex.withLock {
        val pending = listed.filterValues { it.cursor != null }
        if (pending.isEmpty()) return@withLock listed.merged()
        listed = listed + readAll(pending, pages = 1)
        listed.merged()
    }

    private suspend fun readAll(from: Map<AgentId, AgentListing>, pages: Int): Map<AgentId, AgentListing> = coroutineScope {
        from.map { (agent, listing) -> async { agent to readPages(agent, listing, pages) } }.awaitAll().toMap()
    }

    /** Up to [pages] more pages of [agent]'s, from where [start] left off. */
    private suspend fun readPages(agent: AgentId, start: AgentListing, pages: Int): AgentListing {
        var listing = start
        repeat(pages.coerceAtLeast(1)) {
            if (listing.read && listing.cursor == null) return listing
            listing = readPage(agent, listing)
            if (listing.failure != null) return listing
        }
        return listing
    }

    private suspend fun readPage(agent: AgentId, listing: AgentListing): AgentListing {
        val page = AgentConversationPage(agent, limit = paging.pageSize, after = listing.cursor)
        return runCatchingCancellable { permits.withPermit { reader.read(page) } }
            .fold(
                onSuccess = { rows -> listing.plus(agent, rows, full = rows.size >= paging.pageSize) },
                onFailure = { failure ->
                    Telemetry.event(
                        TELEMETRY_TAG, "agent_read_failed",
                        "agentId" to agent.value,
                        "error" to (failure.message ?: failure::class.simpleName.orEmpty()),
                        level = Telemetry.Level.WARN,
                    )
                    listing.copy(failure = failure)
                },
            )
    }

    private companion object {
        const val TELEMETRY_TAG = "ConversationFleet"
        const val LIST_FAILED = "Could not list the conversations"
    }
}

/**
 * One agent's conversations as read so far: [cursor] is where its next page starts (null once
 * read through), [failure] its last read's failure.
 */
private data class AgentListing(
    val rows: List<Conversation> = emptyList(),
    val cursor: ConversationId? = null,
    val read: Boolean = false,
    val failure: Throwable? = null,
) {
    /** These rows and a page more: only [agent]'s own, visible ones; a [full] page may have more after it. */
    fun plus(agent: AgentId, page: List<Conversation>, full: Boolean): AgentListing {
        val ids = rows.mapTo(mutableSetOf()) { it.id }
        val own = page.filter { it.agentId == agent && it.id !in ids }.visibleConversationsInLists()
        // The cursor is the page's last row as the server sent it, so a filtered row never stalls paging.
        return AgentListing(rows = rows + own, cursor = page.lastOrNull()?.id?.takeIf { full }, read = true)
    }
}

private fun Map<AgentId, AgentListing>.merged(): List<Conversation> =
    values.flatMap { it.rows }.distinctBy { it.id }.sortedWith(ConversationRecency.newestFirst)
