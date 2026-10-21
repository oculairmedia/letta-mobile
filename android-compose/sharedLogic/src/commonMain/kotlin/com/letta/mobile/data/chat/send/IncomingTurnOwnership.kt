package com.letta.mobile.data.chat.send

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.transport.WsTimelineEvent

/** Incoming frame routing policy over the sole registry index; caller holds the lifecycle mutex. */
/** Replayed echoes cannot bind a live send; UI publication occurs after this index operation. */
internal fun ConversationTurnRegistry.bindDelta(event: WsTimelineEvent.MessageDelta): ConversationTurnRegistry.Binding? {
    val otid = event.message.otid ?: return null
    if (event.isReplay) return null
    return bindInboundTurnByOtid(
        QueuedSendId(otid), event.conversationId?.let(::ConversationId), TurnTail(event.turnId, event.message.runId),
    )
}

/** One resolved owner for a delta; replay fences determine whether it may change live presence. */
internal data class DeltaResolution(
    val conversation: ConversationId,
    val state: ConversationTurnState?,
    val retired: Boolean,
)

internal fun ConversationTurnRegistry.resolveDelta(
    event: WsTimelineEvent.MessageDelta,
    boundState: ConversationTurnState?,
    visibleConversation: ConversationId?,
): DeltaResolution? {
    val conversation = deltaConversation(event, boundState, visibleConversation) ?: return null
    val retired = peekState(conversation)?.isRetiredDelta(event) == true
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
private fun ConversationTurnRegistry.deltaConversation(
    event: WsTimelineEvent.MessageDelta,
    boundState: ConversationTurnState?,
    visibleConversation: ConversationId?,
): ConversationId? {
    val named = event.conversationId?.let(::ConversationId)
    return if (named != null) namedDeltaConversation(event, boundState, named)
    else legacyDeltaConversation(event, boundState, visibleConversation)
}

/** A named frame never falls through to the visible conversation's fabricated state. */
private fun ConversationTurnRegistry.namedDeltaConversation(
    event: WsTimelineEvent.MessageDelta,
    boundState: ConversationTurnState?,
    named: ConversationId,
): ConversationId {
    val resolved = resolveConversationId(named) ?: named
    val owner = boundState ?: ownedDeltaState(TurnTail(event.turnId, null), resolved) ?: peekState(resolved)
    return owner?.let { ConversationId(it.localConversationId ?: it.conversationId) } ?: resolved
}

/** Only legacy untagged frames may use this chat's active send or visible fallback. */
private fun ConversationTurnRegistry.legacyDeltaConversation(
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

private fun ConversationTurnRegistry.ownedDeltaState(tail: TurnTail, conversation: ConversationId): ConversationTurnState? {
    val turn = tail.turnId?.takeIf { it.isNotBlank() } ?: return null
    val states = statesSnapshot
    states.firstOrNull { it.turnId == turn }?.let { return it }
    states.firstOrNull { it.identity.isFenced(turn) }?.let { return it }
    val awaitingIdentity = states.asSequence().filter { it.turnId == null && it.identity.active != null }
    return awaitingIdentity.singleOrNull {
        conversation.value in listOf(it.conversationId, it.localConversationId)
    }
}

internal fun ConversationTurnRegistry.isRetiredTurn(state: ConversationTurnState, tail: TurnTail): Boolean {
    val turn = tail.turnId?.takeIf { it.isNotBlank() } ?: return false
    return state.turnId != turn && state.identity.isFenced(turn)
}

/** Error frames can create state only for this coordinator's visible conversation. */
internal fun ConversationTurnRegistry.resolveError(
    event: WsTimelineEvent.Error,
    visibleConversation: ConversationId?,
): ConversationTurnState? {
    val named = event.conversationId?.takeIf { it.isNotBlank() }
        ?: return resolveStateByTurn(TurnTail(event.turnId, event.runId))
    val resolved = resolveConversationId(ConversationId(named)) ?: ConversationId(named)
    peekState(resolved)?.let { return it }
    return if (resolved == visibleConversation) stateFor(resolved) else null
}
