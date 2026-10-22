package com.letta.mobile.util

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationRecency
import com.letta.mobile.data.model.visibleConversationsInLists
import com.letta.mobile.data.model.visibleInLists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Every conversation list's page size: rows per read of one agent's conversations. */
const val AGENT_CONVERSATION_PAGE_SIZE: Int = 50

/** How many pages a list with no "load more" reads per agent, reading each agent through. */
const val READ_THROUGH_PAGES: Int = 10

/** Reads one page of [agent]'s own conversations, newest first: [limit] rows after the [after] cursor (none: the newest). */
fun interface AgentConversationReader {
    suspend fun read(agent: AgentId, after: ConversationId?, limit: Int): List<Conversation>
}

/** How a [AgentConversationFleet] pages: rows per read, reads at once, and how many pages a refresh reads eagerly per agent. */
data class AgentConversationFleetPaging(
    val pageSize: Int = AGENT_CONVERSATION_PAGE_SIZE,
    /** Reads at once across the roster. */
    val parallelism: Int = 4,
    /** 1 reads only the newest page (the host pages on with [AgentConversationFleet.loadMore]); see [READ_THROUGH_PAGES]. */
    val eagerPagesPerAgent: Int = 1,
)

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
    /** Hears each agent's failed read (its last rows stay listed). */
    private val onReadFailed: (AgentId, Throwable) -> Unit = { _, _ -> },
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
        return runCatching { permits.withPermit { reader.read(agent, listing.cursor, paging.pageSize) } }
            .fold(
                onSuccess = { rows -> listing.plus(agent, rows, full = rows.size >= paging.pageSize) },
                onFailure = { failure ->
                    if (failure is CancellationException) throw failure
                    onReadFailed(agent, failure)
                    listing.copy(failure = failure)
                },
            )
    }
}

private const val LIST_FAILED = "Could not list the conversations"

/**
 * A read-through [AgentConversationFleet] per source (a session's gateway): the same source keeps its
 * fleet, so an agent whose read fails keeps its last rows; a new source starts a new fleet.
 */
class ReadThroughConversationFleets<S>(private val reader: (S) -> AgentConversationReader) {
    private var current: Pair<S, AgentConversationFleet>? = null

    fun fleetFor(source: S): AgentConversationFleet =
        current?.takeIf { it.first === source }?.second
            ?: AgentConversationFleet(reader(source), AgentConversationFleetPaging(eagerPagesPerAgent = READ_THROUGH_PAGES))
                .also { current = source to it }
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
