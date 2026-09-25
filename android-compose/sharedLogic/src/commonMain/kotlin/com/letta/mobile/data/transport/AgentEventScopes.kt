package com.letta.mobile.data.transport

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile

/**
 * letta-mobile-ztuog: per-agent delivery over one shared [WsChatBridge].
 *
 * The bridge's raw [WsChatBridge.events] carries every agent's frames. Chat coordinators used to
 * collect it directly and drop foreign frames one by one, so after visiting three agents each
 * delta reached three coordinators (`ws.event.foreignAgentDropped` ×1000 in two hours on device).
 * Here every subscription is keyed by agent: [eventsFor] delivers only frames the
 * [AgentEventRouter] attributes to that agent (plus connection-wide and unattributable ones), so a
 * delta for agent A never reaches agent B's coordinator.
 *
 * Lifecycle is owned by [AgentEventAttachment]: a chat that is no longer the selected one detaches
 * as soon as it has no turn in flight, and re-attaches when it is selected again.
 */
class AgentEventScopes internal constructor(private val events: Flow<WsTimelineEvent>) {
    private val router = AgentEventRouter()
    internal val selection = ChatSelection()
    private val census = AgentSubscriberCensus()

    /** One coordinator's attachment; see [AgentEventAttachment]. */
    fun attachment(agentId: AgentId, isBusy: () -> Boolean): AgentEventAttachment =
        AgentEventAttachment(this, agentId, isBusy)

    /** Live subscriptions for [agentId] right now — the leak signal the census reports. */
    fun liveSubscriberCount(agentId: AgentId): Int = census.count(agentId)

    internal fun learnSend(agentId: AgentId, conversationId: ConversationId) =
        router.learnSend(agentId, conversationId)

    /**
     * [agentId]'s frames, in wire order, until [stayAttached] turns false. The predicate is checked
     * before each frame and again right after one is handled — never in the middle of a frame's
     * handling — so a detach can never tear a frame half-applied, and a deselected, idle chat
     * receives nothing further: the next frame of any agent ends its subscription.
     */
    fun eventsFor(agentId: AgentId, stayAttached: () -> Boolean = { true }): Flow<WsTimelineEvent> = flow {
        census.record(agentId, CensusChange.Subscribed)
        try {
            emitAll(
                events.transformWhile { event ->
                    if (!stayAttached()) return@transformWhile false
                    if (deliversTo(agentId, event)) emit(event)
                    stayAttached()
                },
            )
        } finally {
            census.record(agentId, CensusChange.Unsubscribed)
        }
    }

    private fun deliversTo(agentId: AgentId, event: WsTimelineEvent): Boolean =
        when (val owner = router.ownerOf(event)) {
            is EventOwner.Agent -> (owner.agentId == agentId).also { if (!it) census.suppressed() }
            EventOwner.Everyone, EventOwner.Unknown -> true
        }
}

/**
 * letta-mobile-ztuog: which chat the user is looking at.
 *
 * Until anyone calls [select] the selection is unmanaged and every chat counts as selected — the
 * behavior hosts that never express a selection (desktop, tests) keep. Once a host selects, only
 * the selected owner stays attached while idle; everyone else detaches when their turns settle.
 */
internal class ChatSelection {
    val selected = MutableStateFlow<ChatSelectionState>(ChatSelectionState.Unmanaged)

    fun select(owner: AgentEventAttachment) {
        selected.value = ChatSelectionState.Selected(owner)
    }

    /** A no-op while unmanaged: a host that never selects keeps every chat attached. */
    fun selectIfManaged(owner: AgentEventAttachment) {
        if (selected.value != ChatSelectionState.Unmanaged) select(owner)
    }

    fun release(owner: AgentEventAttachment) {
        selected.compareAndSet(ChatSelectionState.Selected(owner), ChatSelectionState.NoneSelected)
    }

    fun isSelected(owner: AgentEventAttachment): Boolean = when (val state = selected.value) {
        ChatSelectionState.Unmanaged -> true
        ChatSelectionState.NoneSelected -> false
        is ChatSelectionState.Selected -> state.owner === owner
    }
}

internal sealed interface ChatSelectionState {
    /** No host has expressed a selection yet: every chat counts as selected. */
    data object Unmanaged : ChatSelectionState

    /** The selected chat went away and no other has been selected since. */
    data object NoneSelected : ChatSelectionState

    data class Selected(val owner: AgentEventAttachment) : ChatSelectionState
}

/**
 * letta-mobile-ztuog: one chat coordinator's subscription lifecycle.
 *
 * Stays attached while its owner is selected or has work in flight ([isBusy]), so switching away
 * mid-turn still lands the turn; detaches once unselected and idle; re-attaches on [select]. The
 * coordinator object — and so its per-conversation turn state — survives detachment, which is what
 * makes re-selection resume rather than restart.
 */
class AgentEventAttachment internal constructor(
    private val scopes: AgentEventScopes,
    private val agentId: AgentId,
    private val isBusy: () -> Boolean,
) {
    fun select() = scopes.selection.select(this)

    fun release() = scopes.selection.release(this)

    /** A send comes from the chat on screen; under a managed selection it takes the selection. */
    fun claimForSend() = scopes.selection.selectIfManaged(this)

    fun shouldStayAttached(): Boolean = scopes.selection.isSelected(this) || isBusy()

    /** This owner's frames across attach/detach cycles; completes only when the collector is cancelled. */
    val deliveries: Flow<WsTimelineEvent> = flow {
        while (true) {
            scopes.selection.selected.first { shouldStayAttached() }
            emitAll(scopes.eventsFor(agentId, ::shouldStayAttached))
            Telemetry.event(
                "AgentEventScopes", "ws.route.detached",
                "agentId" to agentId.value,
                "liveForAgent" to scopes.liveSubscriberCount(agentId),
            )
        }
    }
}

internal enum class CensusChange(val eventName: String, val delta: Int) {
    Subscribed("ws.route.subscribed", 1),
    Unsubscribed("ws.route.unsubscribed", -1),
}

/** Counts live per-agent subscriptions and suppressed foreign frames; reports what would reveal a leak. */
private class AgentSubscriberCensus {
    private val lock = SynchronizedObject()
    private val live = mutableMapOf<AgentId, Int>()
    private var suppressedFrames = 0L

    fun count(agentId: AgentId): Int = synchronized(lock) { live[agentId] ?: 0 }

    fun suppressed() {
        synchronized(lock) { suppressedFrames += 1 }
    }

    fun record(agentId: AgentId, change: CensusChange) {
        val snapshot = synchronized(lock) {
            val next = (live[agentId] ?: 0) + change.delta
            if (next > 0) live[agentId] = next else live.remove(agentId)
            CensusSnapshot(next, live.size, live.values.sum(), suppressedFrames)
        }
        val suspicious = change.delta > 0 && snapshot.looksLeaked()
        Telemetry.event(
            "AgentEventScopes", change.eventName,
            "agentId" to agentId.value,
            "liveForAgent" to snapshot.forAgent,
            "liveAgents" to snapshot.agents,
            "liveTotal" to snapshot.total,
            "foreignSuppressed" to snapshot.suppressed,
            level = if (suspicious) Telemetry.Level.WARN else Telemetry.Level.INFO,
        )
    }
}

private data class CensusSnapshot(val forAgent: Int, val agents: Int, val total: Int, val suppressed: Long) {
    /** The selected agent plus one finishing a turn in the background is expected; more is a leak. */
    fun looksLeaked(): Boolean = forAgent > 1 || agents > MAX_EXPECTED_LIVE_AGENTS

    private companion object {
        const val MAX_EXPECTED_LIVE_AGENTS = 2
    }
}
