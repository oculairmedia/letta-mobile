package com.letta.mobile.data.lanes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session-owned, bounded unread/activity state shared by shell rows and transcript views. It holds
 * no conversation objects, navigation or canvas state, so opening, swapping or closing a view never
 * changes it; a new backend/session graph builds a new owner. It issues no history fetch and no
 * per-agent subscription. When [source] is absent or unavailable, [available] is false and rows show unknown unread,
 * never zero. Forwarded provenance stays with its verified durable owner (os2pc).
 */
class ConversationLaneProjection(scope: CoroutineScope, private val source: LaneActivitySource?) {
    private data class Entry(val agent: String, val unread: Set<String>, val read: Set<String>, val overflowed: Boolean)

    private val mutex = Mutex()
    private var entries: Map<String, Entry> = emptyMap()
    private val mutableActivity = MutableStateFlow<Map<String, LaneActivity>>(emptyMap())

    /** Whether the session currently supports activity arrivals. */
    val available: Boolean get() = source?.available == true

    /** Per conversation id; an absent key is no arrival seen this session (or no producer). */
    val activity: StateFlow<Map<String, LaneActivity>> = mutableActivity.asStateFlow()

    init {
        source?.let { input -> scope.launch(start = CoroutineStart.UNDISPATCHED) { input.arrivals().collect(::accept) } }
    }

    /** Counts [arrival] once: a replay of an unread or already-read identity is not new activity. */
    suspend fun accept(arrival: LaneArrival) = change(arrival.conversation) { entry ->
        when {
            entry == null -> Entry(arrival.agent, setOf(arrival.sourceMessageId), emptySet(), overflowed = false)
            // One conversation has one owner; a frame naming another agent cannot touch its state.
            entry.agent != arrival.agent -> entry
            arrival.sourceMessageId in entry.unread || arrival.sourceMessageId in entry.read -> entry
            entry.unread.size >= MAX_MESSAGES -> entry.copy(overflowed = true)
            else -> entry.copy(unread = entry.unread + arrival.sourceMessageId)
        }
    }

    /**
     * Acknowledges the identities actually rendered by [conversation]'s loaded transcript. Selecting
     * a row, mounting a view or reading another conversation acknowledges nothing; an arrival not yet
     * displayed stays unread, including one concurrent with opening.
     */
    suspend fun displayed(conversation: String, agent: String, messageIds: Set<String>) = change(conversation) { entry ->
        val seen = entry?.unread?.intersect(messageIds).orEmpty()
        if (entry == null || entry.agent != agent || seen.isEmpty()) {
            entry
        } else {
            // Read identities only suppress replays; the oldest are dropped first past the bound.
            entry.copy(unread = entry.unread - seen, read = (entry.read + seen).toList().takeLast(MAX_MESSAGES).toSet(), overflowed = false)
        }
    }

    private suspend fun change(conversation: String, transform: (Entry?) -> Entry?) = mutex.withLock {
        val current = entries[conversation]
        val next = transform(current)
        if (next == null || next == current) return@withLock
        // Most recently touched last; the least recently touched conversation is evicted first.
        entries = (entries - conversation + (conversation to next)).entries.toList()
            .takeLast(MAX_CONVERSATIONS).associate { it.toPair() }
        mutableActivity.value = entries.mapValues { (_, entry) ->
            LaneActivity(LaneActivityAvailability.AVAILABLE, entry.unread.size, entry.overflowed)
        }
    }

    companion object {
        const val MAX_MESSAGES: Int = 512
        const val MAX_CONVERSATIONS: Int = 64
    }
}
