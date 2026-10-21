package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.util.Telemetry
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The single index of conversation turn ownership, aliases and replay fences.
 *
 * The coordinator keeps lifecycle serialization: send, event and settlement mutations acquire its
 * coroutine mutex before using this registry. [turnStateLock] only protects short synchronous index
 * operations. Lock order is lifecycle mutex -> index lock -> [TurnIdentityLifecycle]'s identity lock;
 * nothing suspends or waits for the lifecycle mutex while holding an index or identity lock.
 * Returned states retain their existing identity and are mutated only under the lifecycle mutex.
 * This registry creates no scope, queue or independent lifecycle owner.
 */
internal class ConversationTurnRegistry(private val fallbackConversationId: () -> ConversationId) {
    private val turnStateLock = SynchronizedObject()
    private val turnStates = linkedMapOf<ConversationId, ConversationTurnState>()
    private val conversationAliases = linkedMapOf<ConversationId, ConversationId>()
    private val settledOtids = linkedSetOf<QueuedSendId>()
    // Also published inside identity.acceptSend; a setter must not acquire the index lock there.
    @Volatile var lastActiveConversationId: String? = null

    /** UI publication is performed by the coordinator after releasing the index lock. */
    data class Binding(val state: ConversationTurnState?, val rekeyedConversationId: ConversationId?)

    fun resolveConversationId(conversationId: ConversationId?): ConversationId? {
        val key = conversationId?.takeIf { it.value.isNotBlank() } ?: return null
        return synchronized(turnStateLock) { conversationAliases[key] ?: key }
    }

    fun retainSettledOtid(otid: QueuedSendId?) = synchronized(turnStateLock) {
        val key = otid?.takeIf { it.value.isNotBlank() } ?: return@synchronized
        settledOtids += key
        while (settledOtids.size > MAX_SETTLED_OTIDS) {
            settledOtids.remove(settledOtids.first())
        }
    }

    private fun stateForLocked(conversationId: ConversationId): ConversationTurnState {
        val targetId = conversationAliases[conversationId] ?: conversationId
        val state = turnStates.remove(targetId) ?: ConversationTurnState(targetId.value)
        turnStates[targetId] = state
        evictOverflowStatesLocked(keep = targetId)
        return state
    }

    fun stateFor(conversationId: ConversationId): ConversationTurnState = synchronized(turnStateLock) {
        // Re-insert on every access so map order is RECENCY, not first-seen: a
        // conversation the user keeps returning to must not age out just because
        // it was created first (PR2 review, finding 3).
        stateForLocked(conversationId)
    }

    fun peekState(conversationId: ConversationId?): ConversationTurnState? =
        conversationId?.let {
            synchronized(turnStateLock) {
                val targetId = conversationAliases[it] ?: it
                turnStates[targetId]
            }
        }

    /**
     * A turn that already reached its terminal, or whose otid is fenced as settled, must never be
     * rebound: a replayed echo would otherwise resurrect a finished turn.
     */
    private fun bindableStateLocked(otid: QueuedSendId): ConversationTurnState? {
        if (otid in settledOtids) return null
        return turnStates.values.firstOrNull { it.otid == otid.value }?.takeUnless { it.reachedTerminal }
    }

    /**
     * Points the server's name for this conversation at the one the send originated from. When the
     * turn announced itself first, it left an unlinked state under that server name; absorbing it
     * here is what makes the ordering safe. Returns the originating conversation when that
     * happened, because its presence has to be republished, and null when nothing was absorbed.
     */
    private fun ConversationTurnState.rebindToServerConversationLocked(
        serverConversationId: ConversationId,
        tail: TurnTail,
    ): ConversationId? {
        val originatingConv = ConversationId(localConversationId ?: conversationId)
        val serverConv = serverConversationId
        if (serverConv == originatingConv) return null
        conversationAliases.remove(serverConv)
        conversationAliases[serverConv] = originatingConv
        while (conversationAliases.size > MAX_CONVERSATION_ALIASES) {
            conversationAliases.remove(conversationAliases.keys.first())
        }
        this.serverConversationId = serverConv.value
        val orphan = turnStates.remove(serverConv)?.takeUnless { it === this } ?: return null
        absorbOrphan(orphan, tail)
        Telemetry.event(
            "AdminChatVM", "ws.turnState.rekeyedByOtid",
            "otid" to (otid ?: ""),
            "serverConversationId" to serverConv.value,
            "originatingConversationId" to originatingConv.value,
            "turnId" to (this.turnId ?: ""),
            "runId" to (this.runId ?: ""),
        )
        return originatingConv
    }

    /**
     * Binds an inbound turn to its originating send by [otid].
     *
     * Inbound server frames (e.g. UserMessage echo, AssistantMessage) carry the client's
     * [otid] alongside the server's `conversation_id` and `turn_id`. When the server
     * assigns an unseen conversation id, this binds that turn and conversation alias to the
     * originating send's state (which owns foreground UI) rather than stranding deltas under
     * an unobserved server id.
     *
     * Replay and reconnect safety: settled otids are ignored to guarantee idempotency and
     * prevent resurrecting terminal states.
     *
     * Ordering safety: if TurnStarted arrived before the UserMessage echo, any orphan state
     * created under [serverConversationId] is re-keyed and merged into the originating state.
     */
    fun bindInboundTurnByOtid(
        otid: QueuedSendId,
        serverConversationId: ConversationId?,
        tail: TurnTail,
    ): Binding {
        var rekeyedConversation: ConversationId? = null
        val originating = synchronized(turnStateLock) {
            val state = bindableStateLocked(otid) ?: return@synchronized null
            val serverConversation = serverConversationId?.takeIf { it.value.isNotBlank() }
            rekeyedConversation = serverConversation?.let { state.rebindToServerConversationLocked(it, tail) }
            if (tail.turnId != null && state.turnId == null) state.turnId = tail.turnId
            if (tail.runId != null && state.runId == null) state.runId = tail.runId
            state
        }

        return Binding(originating, rekeyedConversation)
    }

    /**
     * Resolves the state for an incoming [WsTimelineEvent.TurnStarted] event.
     *
     * If an alias was already recorded (e.g. UserMessage echo arrived first with otid),
     * this returns the originating conversation's state. If a state already tracks this
     * turnId, that state is returned. Otherwise, state for the event's conversationId is
     * resolved without heuristic adoption of awaiting sends. If this is an unseen server
     * conversation id and TurnStarted precedes UserMessage, the subsequent UserMessage echo
     * carrying the send's otid will re-key and merge the orphan state.
     */
    fun resolveStateForTurnStarted(event: WsTimelineEvent.TurnStarted): ConversationTurnState =
        synchronized(turnStateLock) {
            val aliasedConv = conversationAliases[ConversationId(event.conversationId)]
            if (aliasedConv != null && aliasedConv.value != event.conversationId) {
                return stateForLocked(aliasedConv)
            }
            turnStates.values.firstOrNull { it.turnId == event.turnId }?.let { return it }
            return stateForLocked(ConversationId(event.conversationId))
        }

    val statesSnapshot: List<ConversationTurnState>
        get() = synchronized(turnStateLock) { turnStates.values.toList() }

    val trackedConversationCount: Int get() = synchronized(turnStateLock) { turnStates.size }

    /**
     * The cap only exists to stop the map growing without bound; it must never
     * cost a LIVE turn its state (PR2 review, finding 3). Settled entries are
     * evicted oldest-first, and a tracking entry is never dropped — the map is
     * allowed to exceed the cap instead, which is telemetered so an unbounded
     * live-turn population cannot hide.
     */
    private fun evictOverflowStatesLocked(keep: ConversationId) {
        if (turnStates.size <= MAX_TRACKED_CONVERSATION_TURN_STATES) return
        var overflow = turnStates.size - MAX_TRACKED_CONVERSATION_TURN_STATES
        val settledOldestFirst = turnStates.entries
            .filter { it.key != keep && !it.value.isTracking }
            .map { it.key }
        for (victimKey in settledOldestFirst) {
            if (overflow <= 0) break
            turnStates.remove(victimKey)
            overflow -= 1
        }
        if (turnStates.size > MAX_TRACKED_CONVERSATION_TURN_STATES) {
            // SENSING (letta-mobile-or40x): every remaining entry owns a live
            // send or turn, so the cap yields rather than clobbering one.
            Telemetry.event(
                "AdminChatVM", "ws.turnState.capExceededByLiveTurns",
                "tracked" to turnStates.size,
                "cap" to MAX_TRACKED_CONVERSATION_TURN_STATES,
            )
        }
    }

    /**
     * Only for frames that carry NO conversation key at all. Prefers the most
     * recently active conversation, then the single tracked conversation.
     *
     * When NOTHING is tracked yet there is no cross-conversation victim to
     * protect, so dropping the frame would be strictly worse than the old
     * behavior: a terminal that arrives before this coordinator ever saw a send
     * (a turn started by a previous process, a replayed terminal after a state
     * reset) would leave the UI streaming forever with nothing left to clear it.
     * That case keeps the legacy target — this coordinator's own conversation,
     * or the synthesized default — and materializes an entry for it.
     */
    fun fallbackState(): ConversationTurnState? {
        synchronized(turnStateLock) {
            lastActiveConversationId?.let { turnStates[ConversationId(it)] }?.let { return it }
            turnStates.values.singleOrNull()?.let { return it }
            // Several conversations are tracked and none of them is an
            // unambiguous owner: guessing here is the defect, so drop instead.
            if (turnStates.isNotEmpty()) return null
        }
        val ownConversationId = fallbackConversationId()
        return stateFor(ownConversationId)
    }

    /**
     * Resolves the conversation that owns a turn-keyed frame.
     *
     * Order matters (PR2 review, finding 2):
     *  1. an exact live turn match;
     *  2. any conversation whose lifecycle has already FENCED this turn id — a
     *     delayed terminal for a turn we settled belongs to that conversation's
     *     history. Without this the fallback below would hand it to whichever
     *     send happens to be waiting now and settle it prematurely;
     *  3. only then, a send still awaiting its `TurnStarted`, and only when
     *     exactly ONE exists — more than one would be guessing across
     *     conversations, which is the bug this PR exists to remove.
     * When some other conversation already owns an identified turn and nothing
     * matches, the frame is foreign: return null so the caller drops it.
     */
    fun resolveStateByTurn(tail: TurnTail): ConversationTurnState? {
        val key = tail.turnId?.takeIf { it.isNotBlank() } ?: return fallbackState()
        val states = statesSnapshot
        states.firstOrNull { it.turnId == key }?.let { return it }
        states.firstOrNull { it.identity.isFenced(key) }?.let { return it }
        return pendingOwnerOrFallback(states) { it.turnId }
    }

    /**
     * Run-keyed variant for frames (SubscribeDone) that carry a run id but no turn
     * id. The turn fence is turn-keyed, so settled RUN ids are retained per state
     * ([ConversationTurnState.settledRunIds]) for the same reason: a delayed
     * subscribe terminal for an already-settled run must not be handed to the send
     * that is currently waiting. Unlike the turn path there is nothing left to do
     * for such a frame, so it resolves to null (dropped) rather than to its
     * settled owner.
     */
    fun resolveStateByRun(tail: TurnTail): ConversationTurnState? {
        val key = tail.runId?.takeIf { it.isNotBlank() } ?: return fallbackState()
        val states = statesSnapshot
        states.firstOrNull { it.runId == key }?.let { return it }
        if (states.any { key in it.settledRunIds }) return null
        return pendingOwnerOrFallback(states) { it.runId }
    }

    /** Both identity domains may adopt only a unique send awaiting its authoritative identity. */
    private fun pendingOwnerOrFallback(
        states: List<ConversationTurnState>,
        identityOf: (ConversationTurnState) -> String?,
    ): ConversationTurnState? {
        val awaiting = states.filter { identityOf(it) == null && it.identity.active != null }
        if (awaiting.size == 1) return awaiting.single()
        if (states.any { identityOf(it) != null }) return null
        return fallbackState()
    }

    /** One resolved owner for a delta; replay fences determine whether it may change live presence. */
    data class DeltaResolution(
        val conversation: ConversationId,
        val state: ConversationTurnState?,
        val retired: Boolean,
    )

    fun resolveDelta(
        event: WsTimelineEvent.MessageDelta,
        boundState: ConversationTurnState?,
        visibleConversation: ConversationId?,
    ): DeltaResolution? {
        val conversation = deltaConversation(event, boundState, visibleConversation) ?: return null
        val retired = isRetiredDelta(event, conversation)
        val state = if (shouldAdoptPassiveDelta(event, boundState, retired)) {
            stateFor(conversation).also {
                if (it.adoptPassiveDelta(event)) lastActiveConversationId = it.localConversationId ?: it.conversationId
            }
        } else {
            peekState(conversation)
        }
        return DeltaResolution(conversation, state, retired)
    }

    /** Named frames follow their own conversation; only untagged legacy frames use visible fallback. */
    private fun deltaConversation(
        event: WsTimelineEvent.MessageDelta,
        boundState: ConversationTurnState?,
        visibleConversation: ConversationId?,
    ): ConversationId? {
        val named = event.conversationId?.let(::ConversationId)
        return if (named != null) namedDeltaConversation(event, boundState, named)
        else legacyDeltaConversation(event, boundState, visibleConversation)
    }

    /** A named frame never falls through to the visible conversation's fabricated state. */
    private fun namedDeltaConversation(
        event: WsTimelineEvent.MessageDelta,
        boundState: ConversationTurnState?,
        named: ConversationId,
    ): ConversationId {
        val resolved = resolveConversationId(named) ?: named
        val owner = boundState ?: ownedDeltaState(TurnTail(event.turnId, null), resolved) ?: peekState(resolved)
        return owner?.let { ConversationId(it.localConversationId ?: it.conversationId) } ?: resolved
    }

    /** Only legacy untagged frames may use this chat's active send or visible fallback. */
    private fun legacyDeltaConversation(
        event: WsTimelineEvent.MessageDelta,
        boundState: ConversationTurnState?,
        visibleConversation: ConversationId?,
    ): ConversationId? {
        val owner = boundState ?: event.turnId?.let { turn ->
            resolveStateByTurn(TurnTail(turn, null))?.takeUnless { isRetiredTurn(it, TurnTail(turn, null)) }
        }
        return owner?.let { ConversationId(it.localConversationId ?: it.conversationId) }
            ?: lastActiveConversationId?.let(::ConversationId)
            ?: visibleConversation
    }

    private fun ownedDeltaState(tail: TurnTail, conversation: ConversationId): ConversationTurnState? {
        val turn = tail.turnId?.takeIf { it.isNotBlank() } ?: return null
        val states = statesSnapshot
        states.firstOrNull { it.turnId == turn }?.let { return it.takeUnless { state -> isRetiredTurn(state, tail) } }
        states.firstOrNull { it.identity.isFenced(turn) }?.let { return it }
        return states.singleOrNull {
            it.turnId == null && it.identity.active != null &&
                (it.conversationId == conversation.value || it.localConversationId == conversation.value)
        }
    }

    fun isRetiredTurn(state: ConversationTurnState, tail: TurnTail): Boolean {
        val turn = tail.turnId?.takeIf { it.isNotBlank() } ?: return false
        return state.turnId != turn && state.identity.isFenced(turn)
    }

    private fun isRetiredDelta(event: WsTimelineEvent.MessageDelta, conversation: ConversationId): Boolean {
        val state = peekState(conversation) ?: return false
        val turn = event.turnId?.takeIf { it.isNotBlank() }
        if (turn != null) return isRetiredTurn(state, TurnTail(turn, null))
        val run = event.message.runId?.takeIf { it.isNotBlank() } ?: return false
        return run != state.runId && run in state.settledRunIds
    }

    /** Error frames can create state only for this coordinator's visible conversation. */
    fun resolveError(event: WsTimelineEvent.Error, visibleConversation: ConversationId?): ConversationTurnState? {
        val named = event.conversationId?.takeIf { it.isNotBlank() }
            ?: return resolveStateByTurn(TurnTail(event.turnId, event.runId))
        val resolved = resolveConversationId(ConversationId(named)) ?: ConversationId(named)
        peekState(resolved)?.let { return it }
        return if (resolved == visibleConversation) stateFor(resolved) else null
    }

    fun resolveTerminal(event: WsTimelineEvent.TurnDone): ConversationTurnState? {
        if (event.turnId.isBlank()) return resolveStateByRun(TurnTail(null, event.runId))
        val states = statesSnapshot
        val runMatch = states.firstOrNull { it.canAdoptTerminal(event) }
        val turnAlreadyKnown = states.any { s -> s.turnId == event.turnId || s.identity.isFenced(event.turnId) }
        if (runMatch != null && !turnAlreadyKnown) {
            return runMatch
        }
        return resolveStateByTurn(TurnTail(event.turnId, event.runId))
    }

    private companion object {
        const val MAX_SETTLED_OTIDS = 16
        const val MAX_CONVERSATION_ALIASES = 64
        const val MAX_TRACKED_CONVERSATION_TURN_STATES = 32
    }
}
